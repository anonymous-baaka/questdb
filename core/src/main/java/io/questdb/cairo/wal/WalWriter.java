/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.wal;

import io.questdb.cairo.*;
import io.questdb.cairo.sql.SymbolTable;
import io.questdb.cairo.sql.TableRecordMetadata;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryA;
import io.questdb.cairo.vm.api.MemoryMA;
import io.questdb.cairo.vm.api.MemoryMAR;
import io.questdb.cairo.vm.api.NullMemory;
import io.questdb.cairo.wal.seq.SequencerMetadataChangeSPI;
import io.questdb.cairo.wal.seq.TableMetadataChange;
import io.questdb.cairo.wal.seq.TableMetadataChangeLog;
import io.questdb.cairo.wal.seq.TableSequencerAPI;
import io.questdb.griffin.engine.functions.constants.Long128Constant;
import io.questdb.griffin.engine.ops.AbstractOperation;
import io.questdb.griffin.engine.ops.AlterOperation;
import io.questdb.griffin.engine.ops.UpdateOperation;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.*;
import io.questdb.std.datetime.millitime.MillisecondClock;
import io.questdb.std.str.Path;
import io.questdb.std.str.SingleCharCharSequence;
import org.jetbrains.annotations.NotNull;

import static io.questdb.cairo.TableUtils.*;
import static io.questdb.cairo.wal.WalUtils.WAL_NAME_BASE;
import static io.questdb.cairo.wal.seq.TableSequencer.NO_TXN;

public class WalWriter implements TableWriterAPI {
    public static final int NEW_COL_RECORD_SIZE = 6;
    private static final long COLUMN_DELETED_NULL_FLAG = Long.MAX_VALUE;
    private static final Log LOG = LogFactory.getLog(WalWriter.class);
    private static final int MEM_TAG = MemoryTag.MMAP_TABLE_WAL_WRITER;
    private static final Runnable NOOP = () -> {
    };
    private final ObjList<MemoryMA> columns;
    private final CairoConfiguration configuration;
    private final WalWriterEvents events;
    private final FilesFacade ff;
    private final AtomicIntList initialSymbolCounts;
    private final WalWriterMetadata metadata;
    private final MetadataValidator metadataValidator = new MetadataValidator();
    private final int mkDirMode;
    private final ObjList<Runnable> nullSetters;
    private final Path path;
    private final int rootLen;
    private final RowImpl row = new RowImpl();
    private final LongList rowValueIsNotNull = new LongList();
    private final MemoryMAR symbolMapMem = Vm.getMARInstance();
    private final ObjList<SymbolMapReader> symbolMapReaders = new ObjList<>();
    private final ObjList<CharSequenceIntHashMap> symbolMaps = new ObjList<>();
    private final String tableName;
    private final TableSequencerAPI tableSequencerAPI;
    private final int walId;
    private final SequencerMetadataChangeSPI walMetadataUpdater = new WalMetadataUpdaterBackend();
    private final String walName;
    private int columnCount;
    private ColumnVersionReader columnVersionReader;
    private long currentTxnStartRowNum = -1;
    private boolean distressed;
    private long lastSegmentTxn = -1L;
    private boolean open;
    private boolean rollSegmentOnNextRow = false;
    private int segmentId = -1;
    private long segmentLockFd = -1;
    private long segmentRowCount = -1;
    private TxReader txReader;
    private long txnMaxTimestamp = -1;
    private long txnMinTimestamp = Long.MAX_VALUE;
    private boolean txnOutOfOrder = false;
    private long walLockFd = -1;

    public WalWriter(CairoConfiguration configuration, String tableName, TableSequencerAPI tableSequencerAPI) {
        LOG.info().$("open '").utf8(tableName).$('\'').$();
        this.tableSequencerAPI = tableSequencerAPI;
        this.configuration = configuration;
        this.mkDirMode = configuration.getMkDirMode();
        this.ff = configuration.getFilesFacade();
        this.tableName = tableName;
        final int walId = tableSequencerAPI.getNextWalId(tableName);
        this.walName = WAL_NAME_BASE + walId;
        this.walId = walId;
        this.path = new Path().of(configuration.getRoot()).concat(tableName).concat(walName);
        this.rootLen = path.length();
        this.open = true;

        try {
            lockWal();
            mkWalDir();

            metadata = new WalWriterMetadata(ff);

            tableSequencerAPI.getTableMetadata(tableName, metadata);

            columnCount = metadata.getColumnCount();
            columns = new ObjList<>(columnCount * 2);
            nullSetters = new ObjList<>(columnCount);
            initialSymbolCounts = new AtomicIntList(columnCount);

            events = new WalWriterEvents(ff);
            events.of(symbolMaps, initialSymbolCounts);

            configureColumns();
            openNewSegment();
            configureSymbolTable();
        } catch (Throwable e) {
            doClose(false);
            throw e;
        }
    }

    @Override
    public long apply(AlterOperation operation, boolean contextAllowsAnyStructureChanges) throws AlterTableContextException {
        if (inTransaction()) {
            throw CairoException.critical(0).put("cannot alter table with uncommitted inserts [table=").put(tableName).put(']');
        }
        if (operation.isStructureChange()) {
            long txn;
            do {
                boolean retry = true;
                try {
                    metadataValidator.startAlterValidation();
                    operation.apply(metadataValidator, true);
                    if (metadataValidator.structureVersion != metadata.getStructureVersion() + 1) {
                        retry = false;
                        throw CairoException.nonCritical()
                                .put("statements containing multiple transactions, such as 'alter table add column col1, col2'" +
                                        " are currently not supported for WAL tables [table=").put(tableName)
                                .put(", oldStructureVersion=").put(metadata.getStructureVersion())
                                .put(", newStructureVersion=").put(metadataValidator.structureVersion).put(']');
                    }
                } catch (CairoException e) {
                    if (retry) {
                        // Table schema (metadata) changed and this Alter is not valid anymore.
                        // Try to update WAL metadata to latest and repeat one more time.
                        goActive();
                        operation.apply(metadataValidator, true);
                    } else {
                        throw e;
                    }
                }

                txn = tableSequencerAPI.nextStructureTxn(tableName, getStructureVersion(), operation);
                if (txn == NO_TXN) {
                    applyMetadataChangeLog(Long.MAX_VALUE);
                }
            } while (txn == NO_TXN);

            // Apply to itself.
            try {
                operation.apply(walMetadataUpdater, true);
            } catch (Throwable th) {
                // Transaction successful, but writing using this WAL writer should not be possible.
                LOG.error().$("Exception during alter [ex=").$(th).I$();
                distressed = true;
            }
            return txn;
        } else {
            return applyNonStructuralOperation(operation);
        }
    }

    // Returns table transaction number
    @Override
    public long apply(UpdateOperation operation) {
        // it is guaranteed that there is no join in UPDATE statement
        // because SqlCompiler rejects the UPDATE if it contains join
        return applyNonStructuralOperation(operation);

        // when join is allowed in UPDATE we have 2 options
        // 1. we could write the updated partitions into WAL.
        //   since we cannot really rely on row ids we should probably create
        //   a PARTITION_REWRITE event and use it here to replace the updated
        //   partitions entirely with new ones.
        // 2. we could still pass the SQL statement if we made sure that all
        //   tables involved in the join are guaranteed to be on the same
        //   version (exact same txn number) on each node when the update
        //   statement is run.
        //   so we would need to read current txn number for each table and
        //   put it into the SQL event as a requirement for running the SQL.
        //   when the WAL event is processed we would need to query the exact
        //   versions of each table involved in the join when running the SQL.
    }

    @Override
    public void close() {
        if (isOpen()) {
            try {
                rollback();
            } finally {
                doClose(true);
            }
        }
    }

    // Returns sequencer transaction number
    @Override
    public long commit() {
        checkDistressed();
        try {
            if (inTransaction()) {
                LOG.debug().$("committing data block [wal=").$(path).$(Files.SEPARATOR).$(segmentId).$(", rowLo=").$(currentTxnStartRowNum).$(", roHi=").$(segmentRowCount).I$();
                lastSegmentTxn = events.data(currentTxnStartRowNum, segmentRowCount, txnMinTimestamp, txnMaxTimestamp, txnOutOfOrder);
                final long seqTxn = getSequencerTxn();
                resetDataTxnProperties();
                mayRollSegmentOnNextRow();
                return seqTxn;
            }
        } catch (Throwable th) {
            if (!isDistressed()) {
                // If distressed, not point to rollback, WalWriter will be not re-used anymore.
                rollback();
            }
            throw th;
        }
        return NO_TXN;
    }

    @Override
    public long commitWithLag() {
        return commit();
    }

    @Override
    public long commitWithLag(long commitLag) {
        return commit();
    }

    public void doClose(boolean truncate) {
        open = false;
        metadata.close(Vm.TRUNCATE_TO_POINTER);
        Misc.free(events);
        freeSymbolMapReaders();
        Misc.free(symbolMapMem);
        freeColumns(truncate);

        releaseSegmentLock();

        try {
            releaseWalLock();
        } finally {
            Misc.free(path);
            LOG.info().$("closed '").utf8(tableName).$('\'').$();
        }
    }

    @Override
    public TableRecordMetadata getMetadata() {
        return metadata;
    }

    public int getSegmentId() {
        return segmentId;
    }

    public long getSegmentRowCount() {
        return segmentRowCount;
    }

    @Override
    public long getStructureVersion() {
        return metadata.getStructureVersion();
    }

    @Override
    public int getSymbolCountWatermark(int columnIndex) {
        // It could be the case that ILP I/O thread has newer metadata version than
        // the writer, so it may be requesting a watermark for a recently added column.
        if (columnIndex > initialSymbolCounts.size() - 1) {
            return 0;
        }
        return initialSymbolCounts.get(columnIndex);
    }

    @Override
    public String getTableName() {
        return tableName;
    }

    @Override
    public long getUncommittedRowCount() {
        return segmentRowCount - currentTxnStartRowNum;
    }

    public int getWalId() {
        return walId;
    }

    public String getWalName() {
        return walName;
    }

    public void goActive() {
        goActive(Long.MAX_VALUE);
    }

    public boolean goActive(long maxStructureVersion) {
        try {
            applyMetadataChangeLog(maxStructureVersion);
            return true;
        } catch (CairoException e) {
            LOG.critical().$("could not apply structure changes, WAL will be closed [table=").$(tableName)
                    .$(", walId=").$(walId)
                    .$(", errno=").$(e.getErrno())
                    .$(", error=").$((Throwable) e).I$();
            distressed = true;
            return false;
        }
    }

    public boolean inTransaction() {
        return segmentRowCount > currentTxnStartRowNum;
    }

    public boolean isDistressed() {
        return distressed;
    }

    public boolean isOpen() {
        return this.open;
    }

    @Override
    public TableWriter.Row newRow() {
        return newRow(0L);
    }

    @Override
    public TableWriter.Row newRow(long timestamp) {
        checkDistressed();
        try {
            if (rollSegmentOnNextRow) {
                rollSegment();
                rollSegmentOnNextRow = false;
            }

            final int timestampIndex = metadata.getTimestampIndex();
            if (timestampIndex != -1) {
                //avoid lookups by having a designated field with primaryColumn
                final MemoryMA primaryColumn = getPrimaryColumn(timestampIndex);
                primaryColumn.putLongLong(timestamp, segmentRowCount);
                setRowValueNotNull(timestampIndex);
                row.timestamp = timestamp;
            }
            return row;
        } catch (Throwable e) {
            distressed = true;
            throw e;
        }
    }

    public void rollUncommittedToNewSegment() {
        final long uncommittedRows = getUncommittedRowCount();
        final int newSegmentId = segmentId + 1;

        path.trimTo(rootLen);

        if (uncommittedRows > 0) {
            createSegmentDir(newSegmentId);
            path.trimTo(rootLen);
            final LongList newColumnFiles = new LongList();
            newColumnFiles.setPos(columnCount * NEW_COL_RECORD_SIZE);
            newColumnFiles.fill(0, columnCount * NEW_COL_RECORD_SIZE, -1);
            rowValueIsNotNull.fill(0, columnCount, -1);

            try {
                final int timestampIndex = metadata.getTimestampIndex();
                LOG.info().$("rolling uncommitted rows to new segment [wal=")
                        .$(path).$(Files.SEPARATOR).$(newSegmentId)
                        .$(", rowCount=").$(uncommittedRows).I$();

                for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                    final int columnType = metadata.getColumnType(columnIndex);
                    if (columnType > 0) {
                        final MemoryMA primaryColumn = getPrimaryColumn(columnIndex);
                        final MemoryMA secondaryColumn = getSecondaryColumn(columnIndex);
                        final String columnName = metadata.getColumnName(columnIndex);

                        CopyWalSegmentUtils.rollColumnToSegment(ff,
                                configuration.getWriterFileOpenOpts(),
                                primaryColumn,
                                secondaryColumn,
                                path,
                                newSegmentId,
                                columnName,
                                columnIndex == timestampIndex ? -columnType : columnType,
                                currentTxnStartRowNum,
                                uncommittedRows,
                                newColumnFiles,
                                columnIndex
                        );
                    } else {
                        rowValueIsNotNull.setQuick(columnIndex, COLUMN_DELETED_NULL_FLAG);
                    }
                }
            } catch (Throwable e) {
                closeSegmentSwitchFiles(newColumnFiles);
                throw e;
            }
            switchColumnsToNewSegment(newColumnFiles);
            rollLastWalEventRecord(newSegmentId, uncommittedRows);
            segmentId = newSegmentId;
            segmentRowCount = uncommittedRows;
            currentTxnStartRowNum = 0;
        } else if (segmentRowCount > 0 && uncommittedRows == 0) {
            rollSegmentOnNextRow = true;
        }
    }

    @Override
    public void rollback() {
        try {
            if (inTransaction() || hasDirtyColumns(currentTxnStartRowNum)) {
                setAppendPosition(currentTxnStartRowNum);
                segmentRowCount = currentTxnStartRowNum;
                txnMinTimestamp = Long.MAX_VALUE;
                txnMaxTimestamp = -1;
                txnOutOfOrder = false;
            }
        } catch (Throwable th) {
            // Set to dissatisfied state, otherwise the pool will keep trying to rollback until the stack overflow
            distressed = true;
            throw th;
        }
    }

    @Override
    public String toString() {
        return "WalWriter{" +
                "name=" + walName +
                ", table=" + tableName +
                '}';
    }

    @Override
    public void truncate() {
        try {
            lastSegmentTxn = events.truncate();
            getSequencerTxn();
        } catch (Throwable th) {
            rollback();
            throw th;
        }
    }

    private static void configureNullSetters(ObjList<Runnable> nullers, int type, MemoryA mem1, MemoryA mem2) {
        switch (ColumnType.tagOf(type)) {
            case ColumnType.BOOLEAN:
            case ColumnType.BYTE:
                nullers.add(() -> mem1.putByte((byte) 0));
                break;
            case ColumnType.DOUBLE:
                nullers.add(() -> mem1.putDouble(Double.NaN));
                break;
            case ColumnType.FLOAT:
                nullers.add(() -> mem1.putFloat(Float.NaN));
                break;
            case ColumnType.INT:
                nullers.add(() -> mem1.putInt(Numbers.INT_NaN));
                break;
            case ColumnType.LONG:
            case ColumnType.DATE:
            case ColumnType.TIMESTAMP:
                nullers.add(() -> mem1.putLong(Numbers.LONG_NaN));
                break;
            case ColumnType.LONG256:
                nullers.add(() -> mem1.putLong256(Numbers.LONG_NaN, Numbers.LONG_NaN, Numbers.LONG_NaN, Numbers.LONG_NaN));
                break;
            case ColumnType.SHORT:
                nullers.add(() -> mem1.putShort((short) 0));
                break;
            case ColumnType.CHAR:
                nullers.add(() -> mem1.putChar((char) 0));
                break;
            case ColumnType.STRING:
                nullers.add(() -> mem2.putLong(mem1.putNullStr()));
                break;
            case ColumnType.SYMBOL:
                nullers.add(() -> mem1.putInt(SymbolTable.VALUE_IS_NULL));
                break;
            case ColumnType.BINARY:
                nullers.add(() -> mem2.putLong(mem1.putNullBin()));
                break;
            case ColumnType.GEOBYTE:
                nullers.add(() -> mem1.putByte(GeoHashes.BYTE_NULL));
                break;
            case ColumnType.GEOSHORT:
                nullers.add(() -> mem1.putShort(GeoHashes.SHORT_NULL));
                break;
            case ColumnType.GEOINT:
                nullers.add(() -> mem1.putInt(GeoHashes.INT_NULL));
                break;
            case ColumnType.GEOLONG:
                nullers.add(() -> mem1.putLong(GeoHashes.NULL));
                break;
            case ColumnType.LONG128:
                nullers.add(() -> mem1.putLong128LittleEndian(Long128Constant.NULL_HI, Long128Constant.NULL_LO));
                break;
            default:
                throw new UnsupportedOperationException("unsupported column type: " + ColumnType.nameOf(type));
        }
    }

    private static void freeNullSetter(ObjList<Runnable> nullSetters, int columnIndex) {
        nullSetters.setQuick(columnIndex, NOOP);
    }

    private static int getPrimaryColumnIndex(int index) {
        return index * 2;
    }

    private static int getSecondaryColumnIndex(int index) {
        return getPrimaryColumnIndex(index) + 1;
    }

    private void applyMetadataChangeLog(long structureVersionHi) {
        try (TableMetadataChangeLog structureChangeCursor = tableSequencerAPI.getMetadataChangeLogCursor(tableName, getStructureVersion())) {
            long metadataVersion = getStructureVersion();
            while (structureChangeCursor.hasNext() && metadataVersion < structureVersionHi) {
                TableMetadataChange tableMetadataChange = structureChangeCursor.next();
                try {
                    tableMetadataChange.apply(walMetadataUpdater, true);
                } catch (CairoException e) {
                    distressed = true;
                    throw e;
                }

                if (++metadataVersion != getStructureVersion()) {
                    distressed = true;
                    throw CairoException.critical(0)
                            .put("could not apply table definition changes to the current transaction, version unchanged");
                }
            }
        }
    }

    private long applyNonStructuralOperation(AbstractOperation operation) {
        if (operation.getSqlExecutionContext() == null) {
            throw CairoException.critical(0).put("failed to commit ALTER SQL to WAL, sql context is empty [table=").put(tableName).put(']');
        }
        try {
            lastSegmentTxn = events.sql(operation.getCommandType(), operation.getSqlStatement(), operation.getSqlExecutionContext());
            return getSequencerTxn();
        } catch (Throwable th) {
            // perhaps half record was written to WAL-e, better to not use this WAL writer instance
            distressed = true;
            throw th;
        }
    }

    private void checkDistressed() {
        if (!distressed) {
            return;
        }
        throw CairoException.critical(0)
                .put("WAL writer is distressed and cannot be used any more [table=").put(tableName)
                .put(", wal=").put(walId).put(']');
    }

    private void cleanupSymbolMapFiles(Path path, int rootLen, CharSequence columnName) {
        path.trimTo(rootLen);
        BitmapIndexUtils.valueFileName(path, columnName, COLUMN_NAME_TXN_NONE);
        ff.remove(path.$());

        path.trimTo(rootLen);
        BitmapIndexUtils.keyFileName(path, columnName, COLUMN_NAME_TXN_NONE);
        ff.remove(path.$());

        path.trimTo(rootLen);
        TableUtils.charFileName(path, columnName, COLUMN_NAME_TXN_NONE);
        ff.remove(path.$());

        path.trimTo(rootLen);
        TableUtils.offsetFileName(path, columnName, COLUMN_NAME_TXN_NONE);
        ff.remove(path.$());
    }

    private void closeSegmentSwitchFiles(LongList newColumnFiles) {
        // Each record is about primary and secondary file. File descriptor is set every half a record.
        int halfRecord = NEW_COL_RECORD_SIZE / 2;
        for (int fdIndex = 0; fdIndex < newColumnFiles.size(); fdIndex += halfRecord) {
            long fd = newColumnFiles.get(fdIndex);
            if (fd > -1L) {
                ff.close(fd);
            }
        }
    }

    private void configureColumn(int index, int columnType) {
        final int baseIndex = getPrimaryColumnIndex(index);
        if (columnType > 0) {
            final MemoryMA primary = Vm.getMAInstance();
            final MemoryMA secondary = createSecondaryMem(columnType);
            columns.extendAndSet(baseIndex, primary);
            columns.extendAndSet(baseIndex + 1, secondary);
            configureNullSetters(nullSetters, columnType, primary, secondary);
            rowValueIsNotNull.add(-1);
        } else {
            columns.extendAndSet(baseIndex, NullMemory.INSTANCE);
            columns.extendAndSet(baseIndex + 1, NullMemory.INSTANCE);
            nullSetters.add(NOOP);
            rowValueIsNotNull.add(COLUMN_DELETED_NULL_FLAG);
        }
    }

    private void configureColumns() {
        for (int i = 0; i < columnCount; i++) {
            configureColumn(i, metadata.getColumnType(i));
        }
    }

    private void configureEmptySymbol(int columnWriterIndex) {
        symbolMapReaders.extendAndSet(columnWriterIndex, EmptySymbolMapReader.INSTANCE);
        initialSymbolCounts.extendAndSet(columnWriterIndex, 0);
        symbolMaps.extendAndSet(columnWriterIndex, new CharSequenceIntHashMap(8, 0.5, SymbolTable.VALUE_NOT_FOUND));
    }

    private void configureSymbolMapWriter(
            int columnWriterIndex,
            CharSequence columnName,
            int symbolCount,
            long columnNameTxn
    ) {
        if (symbolCount == 0) {
            configureEmptySymbol(columnWriterIndex);
            return;
        }

        // Copy or hard link symbol map files.
        FilesFacade ff = configuration.getFilesFacade();
        Path tempPath = Path.PATH.get();
        tempPath.of(configuration.getRoot()).concat(tableName);
        int tempPathTripLen = tempPath.length();

        path.trimTo(rootLen);
        TableUtils.offsetFileName(tempPath, columnName, columnNameTxn);
        TableUtils.offsetFileName(path, columnName, COLUMN_NAME_TXN_NONE);
        if (-1 == ff.hardLink(tempPath.$(), path.$())) {
            // This is fine, Table Writer can rename or drop the column.
            LOG.info().$("failed to link offset file [from=").$(tempPath)
                    .$(", to=").$(path)
                    .$(", errno=").$(ff.errno())
                    .I$();
            configureEmptySymbol(columnWriterIndex);
            return;
        }

        tempPath.trimTo(tempPathTripLen);
        path.trimTo(rootLen);
        TableUtils.charFileName(tempPath, columnName, columnNameTxn);
        TableUtils.charFileName(path, columnName, COLUMN_NAME_TXN_NONE);
        if (-1 == ff.hardLink(tempPath.$(), path.$())) {
            // This is fine, Table Writer can rename or drop the column.
            LOG.info().$("failed to link char file [from=").$(tempPath)
                    .$(", to=").$(path)
                    .$(", errno=").$(ff.errno())
                    .I$();
            cleanupSymbolMapFiles(path, rootLen, columnName);
            configureEmptySymbol(columnWriterIndex);
            return;
        }

        tempPath.trimTo(tempPathTripLen);
        path.trimTo(rootLen);
        BitmapIndexUtils.keyFileName(tempPath, columnName, columnNameTxn);
        BitmapIndexUtils.keyFileName(path, columnName, COLUMN_NAME_TXN_NONE);
        if (-1 == ff.hardLink(tempPath.$(), path.$())) {
            // This is fine, Table Writer can rename or drop the column.
            LOG.info().$("failed to link key file [from=").$(tempPath)
                    .$(", to=").$(path)
                    .$(", errno=").$(ff.errno())
                    .I$();
            cleanupSymbolMapFiles(path, rootLen, columnName);
            configureEmptySymbol(columnWriterIndex);
            return;
        }

        tempPath.trimTo(tempPathTripLen);
        path.trimTo(rootLen);
        BitmapIndexUtils.valueFileName(tempPath, columnName, columnNameTxn);
        BitmapIndexUtils.valueFileName(path, columnName, COLUMN_NAME_TXN_NONE);
        if (-1 == ff.hardLink(tempPath.$(), path.$())) {
            // This is fine, Table Writer can rename or drop the column.
            LOG.info().$("failed to link value file [from=").$(tempPath)
                    .$(", to=").$(path)
                    .$(", errno=").$(ff.errno())
                    .I$();
            cleanupSymbolMapFiles(path, rootLen, columnName);
            configureEmptySymbol(columnWriterIndex);
            return;
        }

        path.trimTo(rootLen);
        SymbolMapReader symbolMapReader = new SymbolMapReaderImpl(
                configuration,
                path,
                columnName,
                COLUMN_NAME_TXN_NONE,
                symbolCount
        );

        symbolMapReaders.extendAndSet(columnWriterIndex, symbolMapReader);
        symbolMaps.extendAndSet(columnWriterIndex, new CharSequenceIntHashMap(8, 0.5, SymbolTable.VALUE_NOT_FOUND));
        initialSymbolCounts.extendAndSet(columnWriterIndex, symbolCount);
    }

    private void configureSymbolTable() {
        boolean initialized = false;
        try {
            int denseSymbolIndex = 0;

            for (int i = 0; i < columnCount; i++) {
                int columnType = metadata.getColumnType(i);
                if (!ColumnType.isSymbol(columnType)) {
                    // Maintain sparse list of symbol writers.
                    // Note: we don't need to set initialSymbolCounts value here, since we already filled it with -1s initially.
                    symbolMapReaders.extendAndSet(i, null);
                    symbolMaps.extendAndSet(i, null);
                } else {
                    if (txReader == null) {
                        txReader = new TxReader(ff);
                        columnVersionReader = new ColumnVersionReader();
                    }

                    if (!initialized) {
                        MillisecondClock milliClock = configuration.getMillisecondClock();
                        long spinLockTimeout = configuration.getSpinLockTimeout();

                        Path path = Path.PATH2.get();
                        path.of(configuration.getRoot()).concat(tableName).concat(TXN_FILE_NAME).$();

                        // Does not matter which PartitionBy, as long as it is partitioned
                        // WAL tables must be partitioned
                        txReader.ofRO(path, PartitionBy.DAY);
                        path.of(configuration.getRoot()).concat(tableName).concat(TableUtils.COLUMN_VERSION_FILE_NAME).$();
                        columnVersionReader.ofRO(ff, path);

                        initialized = true;
                        long structureVersion = getStructureVersion();

                        do {
                            TableUtils.safeReadTxn(txReader, milliClock, spinLockTimeout);
                            if (txReader.getStructureVersion() != structureVersion) {
                                initialized = false;
                                break;
                            }
                            columnVersionReader.readSafe(milliClock, spinLockTimeout);
                        } while (txReader.getColumnVersion() != columnVersionReader.getVersion());
                    }

                    if (initialized) {
                        int symbolValueCount = txReader.getSymbolValueCount(denseSymbolIndex);
                        long columnNameTxn = columnVersionReader.getDefaultColumnNameTxn(i);
                        configureSymbolMapWriter(i, metadata.getColumnName(i), symbolValueCount, columnNameTxn);
                    } else {
                        // table on disk structure version does not match the structure version of the WalWriter
                        // it is not possible to re-use table symbol table because the column name may not match.
                        // The symbol counts stored as dense in _txn file and removal of symbols
                        // shifts the counts that's why it's not possible to find out the symbol count if metadata versions
                        // don't match.
                        configureSymbolMapWriter(i, metadata.getColumnName(i), 0, COLUMN_NAME_TXN_NONE);
                    }
                }

                if (columnType == ColumnType.SYMBOL || columnType == -ColumnType.SYMBOL) {
                    denseSymbolIndex++;
                }
            }
        } finally {
            Misc.free(columnVersionReader);
            Misc.free(txReader);
        }
    }

    private MemoryMA createSecondaryMem(int columnType) {
        switch (ColumnType.tagOf(columnType)) {
            case ColumnType.BINARY:
            case ColumnType.STRING:
                return Vm.getMAInstance();
            default:
                return null;
        }
    }

    private int createSegmentDir(int segmentId) {
        path.trimTo(rootLen);
        path.slash().put(segmentId);
        final int segmentPathLen = path.length();
        rolloverSegmentLock();
        if (ff.mkdirs(path.slash$(), mkDirMode) != 0) {
            throw CairoException.critical(ff.errno()).put("Cannot create WAL segment directory: ").put(path);
        }
        path.trimTo(segmentPathLen);
        return segmentPathLen;
    }

    private void freeAndRemoveColumnPair(ObjList<MemoryMA> columns, int pi, int si) {
        final MemoryMA primaryColumn = columns.getAndSetQuick(pi, NullMemory.INSTANCE);
        final MemoryMA secondaryColumn = columns.getAndSetQuick(si, NullMemory.INSTANCE);
        primaryColumn.close(true, Vm.TRUNCATE_TO_POINTER);
        if (secondaryColumn != null) {
            secondaryColumn.close(true, Vm.TRUNCATE_TO_POINTER);
        }
    }

    private void freeColumns(boolean truncate) {
        // null check is because this method could be called from the constructor
        if (columns != null) {
            for (int i = 0, n = columns.size(); i < n; i++) {
                final MemoryMA m = columns.getQuick(i);
                if (m != null) {
                    m.close(truncate, Vm.TRUNCATE_TO_POINTER);
                }
            }
        }
    }

    private void freeSymbolMapReaders() {
        Misc.freeObjListIfCloseable(symbolMapReaders);
    }

    private MemoryMA getPrimaryColumn(int column) {
        assert column < columnCount : "Column index is out of bounds: " + column + " >= " + columnCount;
        return columns.getQuick(getPrimaryColumnIndex(column));
    }

    private MemoryMA getSecondaryColumn(int column) {
        assert column < columnCount : "Column index is out of bounds: " + column + " >= " + columnCount;
        return columns.getQuick(getSecondaryColumnIndex(column));
    }

    private long getSequencerTxn() {
        long seqTxn;
        do {
            seqTxn = tableSequencerAPI.nextTxn(tableName, walId, metadata.getStructureVersion(), segmentId, lastSegmentTxn);
            if (seqTxn == NO_TXN) {
                applyMetadataChangeLog(Long.MAX_VALUE);
            }
        } while (seqTxn == NO_TXN);
        return seqTxn;
    }

    private boolean hasDirtyColumns(long currentTxnStartRowNum) {
        for (int i = 0; i < columnCount; i++) {
            long writtenCount = rowValueIsNotNull.getQuick(i);
            if (writtenCount >= currentTxnStartRowNum && writtenCount != COLUMN_DELETED_NULL_FLAG) {
                return true;
            }
        }
        return false;
    }

    private void lockWal() {
        try {
            lockName(path);
            walLockFd = TableUtils.lock(ff, path);
        } finally {
            path.trimTo(rootLen);
        }

        if (walLockFd == -1L) {
            throw CairoException.critical(ff.errno()).put("Cannot lock table: ").put(path.$());
        }
    }

    private void markColumnRemoved(int columnIndex) {
        final int pi = getPrimaryColumnIndex(columnIndex);
        final int si = getSecondaryColumnIndex(columnIndex);
        freeNullSetter(nullSetters, columnIndex);
        freeAndRemoveColumnPair(columns, pi, si);
        rowValueIsNotNull.setQuick(columnIndex, COLUMN_DELETED_NULL_FLAG);
    }

    private void mayRollSegmentOnNextRow() {
        if (!rollSegmentOnNextRow && (segmentRowCount >= configuration.getWalSegmentRolloverRowCount())) {
            rollSegmentOnNextRow = true;
        }
    }

    private void mkWalDir() {
        final int walDirLength = path.length();
        if (ff.mkdirs(path.slash$(), mkDirMode) != 0) {
            throw CairoException.critical(ff.errno()).put("Cannot create WAL directory: ").put(path);
        }
        path.trimTo(walDirLength);
    }

    private void openColumnFiles(CharSequence name, int columnIndex, int pathTrimToLen) {
        try {
            final MemoryMA mem1 = getPrimaryColumn(columnIndex);
            mem1.close(true, Vm.TRUNCATE_TO_POINTER);
            mem1.of(ff,
                    dFile(path.trimTo(pathTrimToLen), name),
                    configuration.getDataAppendPageSize(),
                    -1,
                    MemoryTag.MMAP_TABLE_WRITER,
                    configuration.getWriterFileOpenOpts(),
                    Files.POSIX_MADV_RANDOM
            );

            final MemoryMA mem2 = getSecondaryColumn(columnIndex);
            if (mem2 != null) {
                mem2.close(true, Vm.TRUNCATE_TO_POINTER);
                mem2.of(ff,
                        iFile(path.trimTo(pathTrimToLen), name),
                        configuration.getDataAppendPageSize(),
                        -1,
                        MemoryTag.MMAP_TABLE_WRITER,
                        configuration.getWriterFileOpenOpts(),
                        Files.POSIX_MADV_RANDOM
                );
                mem2.putLong(0L);
            }
        } finally {
            path.trimTo(pathTrimToLen);
        }
    }

    private void openNewSegment() {
        try {
            segmentId++;
            currentTxnStartRowNum = 0;
            rowValueIsNotNull.fill(0, columnCount, -1);
            final int segmentPathLen = createSegmentDir(segmentId);

            for (int i = 0; i < columnCount; i++) {
                int type = metadata.getColumnType(i);
                if (type > 0) {
                    final CharSequence name = metadata.getColumnName(i);
                    openColumnFiles(name, i, segmentPathLen);

                    if (type == ColumnType.SYMBOL && symbolMapReaders.size() > 0) {
                        final SymbolMapReader reader = symbolMapReaders.getQuick(i);
                        initialSymbolCounts.set(i, reader.getSymbolCount());
                        CharSequenceIntHashMap symbolMap = symbolMaps.getQuick(i);
                        symbolMap.clear();
                    }
                } else {
                    rowValueIsNotNull.setQuick(i, COLUMN_DELETED_NULL_FLAG);
                }
            }

            segmentRowCount = 0;
            metadata.switchTo(path, segmentPathLen);
            events.openEventFile(path, segmentPathLen);
            lastSegmentTxn = 0;
            LOG.info().$("opened WAL segment [path='").$(path).$('\'').I$();
        } finally {
            path.trimTo(rootLen);
        }
    }

    private void releaseSegmentLock() {
        if (segmentLockFd != -1L) {
            ff.close(segmentLockFd);
            segmentLockFd = -1L;
        }
    }

    private void releaseWalLock() {
        if (walLockFd != -1L) {
            ff.close(walLockFd);
            walLockFd = -1;
        }
    }

    private void removeSymbolMapReader(int index) {
        Misc.freeIfCloseable(symbolMapReaders.getAndSetQuick(index, null));
        symbolMaps.setQuick(index, null);
        initialSymbolCounts.set(index, -1);
        cleanupSymbolMapFiles(path, rootLen, metadata.getColumnName(index));
    }

    private void renameColumnFiles(int columnType, CharSequence columnName, CharSequence newName) {
        path.trimTo(rootLen).slash().put(segmentId);
        final Path tempPath = Path.PATH.get().of(path);

        if (ColumnType.isVariableLength(columnType)) {
            final int trimTo = path.length();
            iFile(path, columnName);
            iFile(tempPath, newName);
            if (ff.rename(path.$(), tempPath.$()) != Files.FILES_RENAME_OK) {
                throw CairoException.critical(ff.errno()).put("could not rename WAL column file [from=").put(path).put(", to=").put(tempPath).put(']');
            }
            path.trimTo(trimTo);
            tempPath.trimTo(trimTo);
        }

        dFile(path, columnName);
        dFile(tempPath, newName);
        if (ff.rename(path.$(), tempPath.$()) != Files.FILES_RENAME_OK) {
            throw CairoException.critical(ff.errno()).put("could not rename WAL column file [from=").put(path).put(", to=").put(tempPath).put(']');
        }
    }

    private void resetDataTxnProperties() {
        currentTxnStartRowNum = segmentRowCount;
        txnMinTimestamp = Long.MAX_VALUE;
        txnMaxTimestamp = -1;
        txnOutOfOrder = false;
        resetSymbolMaps();
    }

    private void resetSymbolMaps() {
        final int numOfColumns = symbolMaps.size();
        for (int i = 0; i < numOfColumns; i++) {
            final CharSequenceIntHashMap symbolMap = symbolMaps.getQuick(i);
            if (symbolMap != null) {
                symbolMap.clear();
            }
            final SymbolMapReader reader = symbolMapReaders.getQuick(i);
            if (reader != null) {
                initialSymbolCounts.set(i, reader.getSymbolCount());
            }
        }
    }

    private void rollLastWalEventRecord(int newSegmentId, long uncommittedRows) {
        events.rollback();
        path.trimTo(rootLen).slash().put(newSegmentId);
        events.openEventFile(path, path.length());
        lastSegmentTxn = events.data(0, uncommittedRows, txnMinTimestamp, txnMaxTimestamp, txnOutOfOrder);
    }

    private void rolloverSegmentLock() {
        releaseSegmentLock();
        final int segmentPathLen = path.length();
        try {
            lockName(path);
            segmentLockFd = TableUtils.lock(ff, path);
            if (segmentLockFd == -1L) {
                path.trimTo(segmentPathLen);
                throw CairoException.critical(ff.errno()).put("Cannot lock wal segment: ").put(path.$());
            }
        } finally {
            path.trimTo(segmentPathLen);
        }
    }

    private void rowAppend(ObjList<Runnable> activeNullSetters, long rowTimestamp) {
        for (int i = 0; i < columnCount; i++) {
            if (rowValueIsNotNull.getQuick(i) < segmentRowCount) {
                activeNullSetters.getQuick(i).run();
            }
        }

        if (rowTimestamp > txnMaxTimestamp) {
            txnMaxTimestamp = rowTimestamp;
        } else {
            txnOutOfOrder |= (txnMaxTimestamp != rowTimestamp);
        }
        if (rowTimestamp < txnMinTimestamp) {
            txnMinTimestamp = rowTimestamp;
        }

        segmentRowCount++;
    }

    private void setAppendPosition(final long segmentRowCount) {
        for (int i = 0; i < columnCount; i++) {
            setColumnSize(i, segmentRowCount);
            int type = metadata.getColumnType(i);
            if (type > 0) {
                rowValueIsNotNull.setQuick(i, segmentRowCount - 1);
            }
        }
    }

    private void setColumnNull(int columnType, int columnIndex, long rowCount) {
        if (ColumnType.isVariableLength(columnType)) {
            setVarColumnVarFileNull(columnType, columnIndex, rowCount);
            setVarColumnFixedFileNull(columnType, columnIndex, rowCount);
        } else {
            setFixColumnNulls(columnType, columnIndex, rowCount);
        }
    }

    private void setColumnSize(int columnIndex, long size) {
        MemoryMA mem1 = getPrimaryColumn(columnIndex);
        MemoryMA mem2 = getSecondaryColumn(columnIndex);
        int type = metadata.getColumnType(columnIndex);
        if (type > 0) { // Not deleted
            if (size > 0) {
                // subtract column top
                final long m1pos;
                switch (ColumnType.tagOf(type)) {
                    case ColumnType.BINARY:
                    case ColumnType.STRING:
                        assert mem2 != null;
                        // Jump to the number of records written to read length of var column correctly
                        mem2.jumpTo(size * Long.BYTES);
                        m1pos = Unsafe.getUnsafe().getLong(mem2.getAppendAddress());
                        // Jump to the end of file to correctly trim the file
                        mem2.jumpTo((size + 1) * Long.BYTES);
                        break;
                    default:
                        if (columnIndex == metadata.getTimestampIndex()) {
                            m1pos = size << 4;
                        } else {
                            m1pos = size << ColumnType.pow2SizeOf(type);
                        }
                        break;
                }
                mem1.jumpTo(m1pos);
            } else {
                mem1.jumpTo(0);
                if (mem2 != null) {
                    mem2.jumpTo(0);
                    mem2.putLong(0);
                }
            }
        }
    }

    private void setFixColumnNulls(int type, int columnIndex, long rowCount) {
        MemoryMA fixedSizeColumn = getPrimaryColumn(columnIndex);
        long columnFileSize = rowCount * ColumnType.sizeOf(type);
        fixedSizeColumn.jumpTo(columnFileSize);
        if (columnFileSize > 0) {
            long address = TableUtils.mapRW(ff, fixedSizeColumn.getFd(), columnFileSize, MEM_TAG);
            try {
                TableUtils.setNull(type, address, rowCount);
            } finally {
                ff.munmap(address, columnFileSize, MEM_TAG);
            }
        }
    }

    private void setRowValueNotNull(int columnIndex) {
        assert rowValueIsNotNull.getQuick(columnIndex) != segmentRowCount;
        rowValueIsNotNull.setQuick(columnIndex, segmentRowCount);
    }

    private void setVarColumnFixedFileNull(int columnType, int columnIndex, long rowCount) {
        MemoryMA fixedSizeColumn = getSecondaryColumn(columnIndex);
        long fixedSizeColSize = (rowCount + 1) * Long.BYTES;
        fixedSizeColumn.jumpTo(fixedSizeColSize);
        if (rowCount > 0) {
            long addressFixed = TableUtils.mapRW(ff, fixedSizeColumn.getFd(), fixedSizeColSize, MEM_TAG);
            try {
                if (columnType == ColumnType.STRING) {
                    Vect.setVarColumnRefs32Bit(addressFixed, 0, rowCount + 1);
                } else {
                    Vect.setVarColumnRefs64Bit(addressFixed, 0, rowCount + 1);
                }
            } finally {
                ff.munmap(addressFixed, fixedSizeColSize, MEM_TAG);
            }
        }
    }

    private void setVarColumnVarFileNull(int columnType, int columnIndex, long rowCount) {
        MemoryMA varColumn = getPrimaryColumn(columnIndex);
        long varColSize = rowCount * ColumnType.variableColumnLengthBytes(columnType);
        varColumn.jumpTo(varColSize);
        if (rowCount > 0) {
            long address = TableUtils.mapRW(ff, varColumn.getFd(), varColSize, MEM_TAG);
            try {
                Vect.memset(address, varColSize, -1);
            } finally {
                ff.munmap(address, varColSize, MEM_TAG);
            }
        }
    }

    private void switchColumnsToNewSegment(LongList newColumnFiles) {
        for (int i = 0; i < columnCount; i++) {
            long newPrimaryFd = newColumnFiles.get(i * NEW_COL_RECORD_SIZE);
            if (newPrimaryFd > -1L) {
                MemoryMA primaryColumnFile = getPrimaryColumn(i);
                long currentOffset = newColumnFiles.get(i * NEW_COL_RECORD_SIZE + 1);
                long newOffset = newColumnFiles.get(i * NEW_COL_RECORD_SIZE + 2);
                primaryColumnFile.jumpTo(currentOffset);
                primaryColumnFile.switchTo(newPrimaryFd, newOffset, Vm.TRUNCATE_TO_POINTER);

                long newSecondaryFd = newColumnFiles.get(i * NEW_COL_RECORD_SIZE + 3);
                if (newSecondaryFd > -1L) {
                    MemoryMA secondaryColumnFile = getSecondaryColumn(i);
                    currentOffset = newColumnFiles.get(i * NEW_COL_RECORD_SIZE + 4);
                    newOffset = newColumnFiles.get(i * NEW_COL_RECORD_SIZE + 5);
                    secondaryColumnFile.jumpTo(currentOffset);
                    secondaryColumnFile.switchTo(newSecondaryFd, newOffset, Vm.TRUNCATE_TO_POINTER);
                }
            }
        }
    }

    SymbolMapReader getSymbolMapReader(int columnIndex) {
        return symbolMapReaders.getQuick(columnIndex);
    }

    void rollSegment() {
        try {
            openNewSegment();
        } catch (Throwable e) {
            distressed = true;
            throw e;
        }
    }

    private class MetadataValidator implements SequencerMetadataChangeSPI {
        public long structureVersion;

        @Override
        public void addColumn(
                CharSequence columnName,
                int columnType,
                int symbolCapacity,
                boolean symbolCacheFlag,
                boolean isIndexed,
                int indexValueBlockCapacity,
                boolean isSequential
        ) {
            if (!TableUtils.isValidColumnName(columnName, columnName.length())) {
                throw CairoException.nonCritical().put("invalid column name: ").put(columnName);
            }
            if (metadata.getColumnIndexQuiet(columnName) > -1) {
                throw CairoException.nonCritical().put("duplicate column name: ").put(columnName);
            }
            if (columnType <= 0 || columnType >= ColumnType.MAX) {
                throw CairoException.nonCritical().put("invalid column type: ").put(columnType);
            }
            structureVersion++;
        }

        @Override
        public TableRecordMetadata getMetadata() {
            return metadata;
        }

        @Override
        public CharSequence getTableName() {
            return tableName;
        }

        @Override
        public void removeColumn(CharSequence columnName) {
            int columnIndex = metadata.getColumnIndexQuiet(columnName);
            if (columnIndex < 0 || metadata.getColumnType(columnIndex) < 0) {
                throw CairoException.nonCritical().put("cannot remove column, column does not exists [table=").put(tableName)
                        .put(", column=").put(columnName).put(']');
            }

            if (columnIndex == metadata.getTimestampIndex()) {
                throw CairoException.nonCritical().put("cannot remove designated timestamp column [table=").put(tableName)
                        .put(", column=").put(columnName);
            }
            structureVersion++;
        }

        @Override
        public void renameColumn(CharSequence columnName, CharSequence newName) {
            int columnIndex = metadata.getColumnIndexQuiet(columnName);
            if (columnIndex < 0) {
                throw CairoException.nonCritical().put("cannot rename column, column does not exists [table=").put(tableName)
                        .put(", column=").put(columnName).put(']');
            }
            if (columnIndex == metadata.getTimestampIndex()) {
                throw CairoException.nonCritical().put("cannot rename designated timestamp column [table=").put(tableName)
                        .put(", column=").put(columnName).put(']');
            }

            int columnIndexNew = metadata.getColumnIndexQuiet(newName);
            if (columnIndexNew > -1) {
                throw CairoException.nonCritical().put("cannot rename column, column with the name already exists [table=").put(tableName)
                        .put(", newName=").put(newName).put(']');
            }
            if (!TableUtils.isValidColumnName(newName, newName.length())) {
                throw CairoException.nonCritical().put("invalid column name: ").put(newName);
            }
            structureVersion++;
        }

        public void startAlterValidation() {
            structureVersion = metadata.getStructureVersion();
        }
    }

    private class RowImpl implements TableWriter.Row {
        private long timestamp;

        @Override
        public void append() {
            rowAppend(nullSetters, timestamp);
        }

        @Override
        public void cancel() {
            setAppendPosition(segmentRowCount);
        }

        @Override
        public void putBin(int columnIndex, long address, long len) {
            getSecondaryColumn(columnIndex).putLong(getPrimaryColumn(columnIndex).putBin(address, len));
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putBin(int columnIndex, BinarySequence sequence) {
            getSecondaryColumn(columnIndex).putLong(getPrimaryColumn(columnIndex).putBin(sequence));
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putBool(int columnIndex, boolean value) {
            getPrimaryColumn(columnIndex).putBool(value);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putByte(int columnIndex, byte value) {
            getPrimaryColumn(columnIndex).putByte(value);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putChar(int columnIndex, char value) {
            getPrimaryColumn(columnIndex).putChar(value);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putDouble(int columnIndex, double value) {
            getPrimaryColumn(columnIndex).putDouble(value);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putFloat(int columnIndex, float value) {
            getPrimaryColumn(columnIndex).putFloat(value);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putGeoHash(int index, long value) {
            int type = metadata.getColumnType(index);
            WriterRowUtils.putGeoHash(index, value, type, this);
        }

        @Override
        public void putGeoHashDeg(int index, double lat, double lon) {
            final int type = metadata.getColumnType(index);
            WriterRowUtils.putGeoHash(index, GeoHashes.fromCoordinatesDegUnsafe(lat, lon, ColumnType.getGeoHashBits(type)), type, this);
        }

        @Override
        public void putGeoStr(int index, CharSequence hash) {
            final int type = metadata.getColumnType(index);
            WriterRowUtils.putGeoStr(index, hash, type, this);
        }

        @Override
        public void putInt(int columnIndex, int value) {
            getPrimaryColumn(columnIndex).putInt(value);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putLong(int columnIndex, long value) {
            getPrimaryColumn(columnIndex).putLong(value);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putLong128LittleEndian(int columnIndex, long hi, long lo) {
            MemoryA primaryColumn = getPrimaryColumn(columnIndex);
            primaryColumn.putLong(lo);
            primaryColumn.putLong(hi);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putLong256(int columnIndex, long l0, long l1, long l2, long l3) {
            getPrimaryColumn(columnIndex).putLong256(l0, l1, l2, l3);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putLong256(int columnIndex, Long256 value) {
            getPrimaryColumn(columnIndex).putLong256(value.getLong0(), value.getLong1(), value.getLong2(), value.getLong3());
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putLong256(int columnIndex, CharSequence hexString) {
            getPrimaryColumn(columnIndex).putLong256(hexString);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putLong256(int columnIndex, @NotNull CharSequence hexString, int start, int end) {
            getPrimaryColumn(columnIndex).putLong256(hexString, start, end);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putShort(int columnIndex, short value) {
            getPrimaryColumn(columnIndex).putShort(value);
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putStr(int columnIndex, CharSequence value) {
            getSecondaryColumn(columnIndex).putLong(getPrimaryColumn(columnIndex).putStr(value));
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putStr(int columnIndex, char value) {
            getSecondaryColumn(columnIndex).putLong(getPrimaryColumn(columnIndex).putStr(value));
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putStr(int columnIndex, CharSequence value, int pos, int len) {
            getSecondaryColumn(columnIndex).putLong(getPrimaryColumn(columnIndex).putStr(value, pos, len));
            setRowValueNotNull(columnIndex);
        }

        @Override
        public void putSym(int columnIndex, CharSequence value) {
            final SymbolMapReader symbolMapReader = symbolMapReaders.getQuick(columnIndex);
            if (symbolMapReader != null) {
                int key = symbolMapReader.keyOf(value);
                if (key == SymbolTable.VALUE_NOT_FOUND) {
                    if (value != null) {
                        // Add it to in-memory symbol map
                        int initialSymCount = initialSymbolCounts.get(columnIndex);
                        CharSequenceIntHashMap symbolMap = symbolMaps.getQuick(columnIndex);
                        key = symbolMap.get(value);
                        if (key == SymbolTable.VALUE_NOT_FOUND) {
                            key = initialSymCount + symbolMap.size();
                            symbolMap.put(value, key);
                        }
                    } else {
                        key = SymbolTable.VALUE_IS_NULL;
                    }
                }
                getPrimaryColumn(columnIndex).putInt(key);
                setRowValueNotNull(columnIndex);
            } else {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public void putSym(int columnIndex, char value) {
            CharSequence str = SingleCharCharSequence.get(value);
            putSym(columnIndex, str);
        }

        private MemoryA getPrimaryColumn(int columnIndex) {
            return columns.getQuick(getPrimaryColumnIndex(columnIndex));
        }

        private MemoryA getSecondaryColumn(int columnIndex) {
            return columns.getQuick(getSecondaryColumnIndex(columnIndex));
        }
    }

    private class WalMetadataUpdaterBackend implements SequencerMetadataChangeSPI {

        @Override
        public void addColumn(
                CharSequence columnName,
                int columnType,
                int symbolCapacity,
                boolean symbolCacheFlag,
                boolean isIndexed,
                int indexValueBlockCapacity,
                boolean isSequential
        ) {
            int columnIndex = metadata.getColumnIndexQuiet(columnName);

            if (columnIndex < 0 || metadata.getColumnType(columnIndex) < 0) {
                long uncommittedRows = getUncommittedRowCount();
                if (currentTxnStartRowNum > 0) {
                    // Roll last transaction to new segment
                    rollUncommittedToNewSegment();
                }

                if (currentTxnStartRowNum == 0 || segmentRowCount == currentTxnStartRowNum) {
                    long segmentRowCount = getUncommittedRowCount();
                    metadata.addColumn(columnName, columnType);
                    columnCount = metadata.getColumnCount();
                    columnIndex = columnCount - 1;
                    // create column file
                    configureColumn(columnIndex, columnType);
                    if (ColumnType.isSymbol(columnType)) {
                        configureSymbolMapWriter(columnIndex, columnName, 0, -1);
                    }

                    if (!rollSegmentOnNextRow) {
                        // this means we have rolled uncommitted rows to a new segment already
                        // we should switch metadata to this new segment
                        path.trimTo(rootLen).slash().put(segmentId);
                        // this will close old _meta file and create the new one
                        metadata.switchTo(path, path.length());
                        openColumnFiles(columnName, columnIndex, path.length());
                    }
                    // if we did not have to roll uncommitted rows to a new segment
                    // it will add the column file and switch metadata file on next row write
                    // as part of rolling to a new segment

                    if (uncommittedRows > 0) {
                        setColumnNull(columnType, columnIndex, segmentRowCount);
                    }
                    LOG.info().$("added column to WAL [path=").$(path).$(Files.SEPARATOR).$(segmentId).$(", columnName=").$(columnName).I$();
                } else {
                    throw CairoException.critical(0).put("column '").put(columnName)
                            .put("' was added, cannot apply commit because of concurrent table definition change");
                }
            } else {
                if (metadata.getColumnType(columnIndex) == columnType) {
                    // TODO: this should be some kind of warning probably that different wals adding the same column concurrently
                    LOG.info().$("column has already been added by another WAL [path=").$(path).$(", columnName=").$(columnName).I$();
                } else {
                    throw CairoException.nonCritical().put("column '").put(columnName).put("' already exists");
                }
            }
        }

        @Override
        public TableRecordMetadata getMetadata() {
            return metadata;
        }

        @Override
        public CharSequence getTableName() {
            return tableName;
        }

        @Override
        public void removeColumn(CharSequence columnName) {
            final int columnIndex = metadata.getColumnIndexQuiet(columnName);
            if (columnIndex > -1) {
                int type = metadata.getColumnType(columnIndex);
                if (type > 0) {
                    if (currentTxnStartRowNum > 0) {
                        // Roll last transaction to new segment
                        rollUncommittedToNewSegment();
                    }

                    if (currentTxnStartRowNum == 0 || segmentRowCount == currentTxnStartRowNum) {
                        int index = metadata.getColumnIndex(columnName);
                        metadata.removeColumn(columnName);
                        columnCount = metadata.getColumnCount();

                        if (!rollSegmentOnNextRow) {
                            // this means we have rolled uncommitted rows to a new segment already
                            // we should switch metadata to this new segment
                            path.trimTo(rootLen).slash().put(segmentId);
                            // this will close old _meta file and create the new one
                            metadata.switchTo(path, path.length());
                        }
                        // if we did not have to roll uncommitted rows to a new segment
                        // it will switch metadata file on next row write
                        // as part of rolling to a new segment

                        if (ColumnType.isSymbol(type)) {
                            removeSymbolMapReader(index);
                        }
                        markColumnRemoved(index);
                        LOG.info().$("removed column from WAL [path=").$(path).$(", columnName=").$(columnName).I$();
                    } else {
                        throw CairoException.critical(0).put("column '").put(columnName)
                                .put("' was removed, cannot apply commit because of concurrent table definition change");
                    }
                }
            } else {
                throw CairoException.nonCritical().put("column '").put(columnName).put("' does not exists");
            }
        }

        @Override
        public void renameColumn(CharSequence columnName, CharSequence newColumnName) {
            final int columnIndex = metadata.getColumnIndexQuiet(columnName);
            if (columnIndex > -1) {
                int columnType = metadata.getColumnType(columnIndex);
                if (columnType > 0) {
                    if (currentTxnStartRowNum > 0) {
                        // Roll last transaction to new segment
                        rollUncommittedToNewSegment();
                    }

                    if (currentTxnStartRowNum == 0 || segmentRowCount == currentTxnStartRowNum) {
                        metadata.renameColumn(columnName, newColumnName);
                        // We are not going to do any special for symbol readers which point
                        // to the files in the root of the table.
                        // We keep the symbol readers open against files with old name.
                        // Inconsistency between column name and symbol file names in the root
                        // does not matter, these files are for re-lookup only for the WAL writer
                        // and should not be serialised to the WAL segment.

                        if (!rollSegmentOnNextRow) {
                            // this means we have rolled uncommitted rows to a new segment already
                            // we should switch metadata to this new segment
                            path.trimTo(rootLen).slash().put(segmentId);
                            // this will close old _meta file and create the new one
                            metadata.switchTo(path, path.length());
                            renameColumnFiles(columnType, columnName, newColumnName);
                        }
                        // if we did not have to roll uncommitted rows to a new segment
                        // it will switch metadata file on next row write
                        // as part of rolling to a new segment

                        LOG.info().$("renamed column in wal [path=").$(path).$(", columnName=").$(columnName).$(", newColumnName=").$(newColumnName).I$();
                    } else {
                        throw CairoException.critical(0).put("column '").put(columnName)
                                .put("' was removed, cannot apply commit because of concurrent table definition change");
                    }
                }
            } else {
                throw CairoException.nonCritical().put("column '").put(columnName).put("' does not exists");
            }
        }
    }
}
