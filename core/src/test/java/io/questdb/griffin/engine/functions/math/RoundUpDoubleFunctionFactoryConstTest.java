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

package io.questdb.griffin.engine.functions.math;

import io.questdb.griffin.AbstractGriffinTest;
import io.questdb.griffin.SqlException;
import org.junit.Test;

public class RoundUpDoubleFunctionFactoryConstTest extends AbstractGriffinTest {


    @Test
    public void testLargeNegScale() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "NaN\n",
                "select round_up(14.7778, -18) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testLargePosScale() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "NaN\n",
                "select round_up(14.7778, 18) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testNegScaleHigherThanNumber() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "-100000.0\n",
                "select round_up(-14.778, -5) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testNegScaleNegValue() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "-20.0\n",
                "select round_up(-14.778, -1) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testNegScalePosValue() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "20.0\n",
                "select round_up(14.778, -1) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testOKNegScale() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "0.0\n",
                "select round_up(14.7778, -17) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testOKPosScale() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "14.777800000000001\n",
                "select round_up(14.7778, 17) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testPosScaleHigherThanNumber() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "-14.7780001\n",
                "select round_up(-14.778, 7) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testPosScaleNegValue() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "-101.0\n",
                "select round_up(-100.9999, 1) from long_sequence(1)",
                null,
                true,
                true
        );
    }

    @Test
    public void testPosScalePosValue() throws SqlException {
        assertQuery(
                "round_up\n" +
                        "100.10000000000001\n",
                "select round_up(100.01, 1) from long_sequence(1)",
                null,
                true,
                true
        );
    }

}