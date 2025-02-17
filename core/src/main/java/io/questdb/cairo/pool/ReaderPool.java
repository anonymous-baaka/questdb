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

package io.questdb.cairo.pool;

import io.questdb.MessageBus;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.TableReader;

public class ReaderPool extends AbstractMultiTenantPool<ReaderPool.R> {

    private final MessageBus messageBus;

    public ReaderPool(CairoConfiguration configuration, MessageBus messageBus) {
        super(configuration);
        this.messageBus = messageBus;
    }

    @Override
    protected byte getListenerSrc() {
        return PoolListener.SRC_READER;
    }

    @Override
    protected R newTenant(String tableName, Entry<R> entry, int index) {
        return new R(this, entry, index, tableName, messageBus);
    }

    public static class R extends TableReader implements PoolTenant {
        private final int index;
        private Entry<R> entry;
        private AbstractMultiTenantPool<R> pool;

        public R(AbstractMultiTenantPool<R> pool, Entry<R> entry, int index, CharSequence name, MessageBus messageBus) {
            super(pool.getConfiguration(), name, messageBus);
            this.pool = pool;
            this.entry = entry;
            this.index = index;
        }

        @Override
        public void close() {
            if (isOpen()) {
                goPassive();
                final AbstractMultiTenantPool<R> pool = this.pool;
                if (pool != null && entry != null) {
                    if (pool.returnToPool(this)) {
                        return;
                    }
                }
                super.close();
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public Entry<R> getEntry() {
            return entry;
        }

        @Override
        public int getIndex() {
            return index;
        }

        public void goodbye() {
            entry = null;
            pool = null;
        }

        @Override
        public void refresh() {
            try {
                goActive();
            } catch (Throwable ex) {
                close();
                throw ex;
            }
        }
    }
}