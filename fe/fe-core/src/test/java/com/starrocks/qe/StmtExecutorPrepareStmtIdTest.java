// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.qe;

import com.starrocks.mysql.MysqlChannel;
import com.starrocks.mysql.MysqlCommand;
import com.starrocks.sql.ast.PrepareStmt;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link StmtExecutor#sendStmtPrepareOK(PrepareStmt)}.
 *
 * <p>Focus on the logic that converts {@link PrepareStmt#getName()} into the numeric statement_id sent back
 * to the client.  Three scenarios are verified:
 *  1) name is a numeric string that fits in 4-byte unsigned range.
 *  2) name is a numeric string that exceeds 4-byte range.
 *  3) name is not numeric.</p>
 */
public class StmtExecutorPrepareStmtIdTest {

    /**
     * A minimal {@link MysqlChannel} implementation that captures packets in memory so we can inspect them
     * without opening a real network socket.
     */
    private static class DummyMysqlChannel extends MysqlChannel {
        private final List<ByteBuffer> packets = new ArrayList<>();

        DummyMysqlChannel() {
            super(null);
        }

        @Override
        public void sendOnePacket(ByteBuffer packet) throws IOException {
            ByteBuffer copy = ByteBuffer.allocate(packet.remaining());
            copy.put(packet);
            copy.flip();
            packets.add(copy);
        }

        @Override
        public void flush() {
            // no-op for tests
        }

        List<ByteBuffer> getPackets() {
            return packets;
        }
    }

    @BeforeClass
    public static void setUpClass() {
        UtFrameUtils.createMinStarRocksCluster();
    }

    private void assertMappingCreated(String stmtName) throws Exception {
        ConnectContext ctx = UtFrameUtils.createDefaultCtx();

        // inject dummy channel
        DummyMysqlChannel dummy = new DummyMysqlChannel();
        Field f = ConnectContext.class.getDeclaredField("mysqlChannel");
        f.setAccessible(true);
        f.set(ctx, dummy);

        ctx.setCommand(MysqlCommand.COM_STMT_PREPARE);

        QueryStatement inner = (QueryStatement) UtFrameUtils.parseStmtWithNewParser("select 1", ctx);
        PrepareStmt p = new PrepareStmt(stmtName, inner, null);

        ctx.putPreparedStmt(stmtName, new PrepareStmtContext(p, ctx, null));

        StmtExecutor executor = new StmtExecutor(ctx, p);
        Method m = StmtExecutor.class.getDeclaredMethod("sendStmtPrepareOK", PrepareStmt.class);
        m.setAccessible(true);
        m.invoke(executor, p);

        String key = String.valueOf(computeKey(stmtName));
        Assert.assertNotNull("PreparedStmtContext not mapped for " + stmtName, ctx.getPreparedStmt(key));
    }

    @Test
    public void testNumericWithinRange() throws Exception {
        assertMappingCreated("123");
    }

    @Test
    public void testNumericOutOfRange() throws Exception {
        assertMappingCreated("5000000000");
    }

    @Test
    public void testNegativeNumeric() throws Exception {
        assertMappingCreated("-5000000000");
    }

    @Test
    public void testNonNumericName() throws Exception {
        assertMappingCreated("stmtABC");
    }

    @Test
    public void testExistingNumericKeyNotOverwritten() throws Exception {
        String name = "6000000000"; // large numeric => hash path

        // build context
        ConnectContext ctx = UtFrameUtils.createDefaultCtx();
        DummyMysqlChannel dummy = new DummyMysqlChannel();
        Field f = ConnectContext.class.getDeclaredField("mysqlChannel");
        f.setAccessible(true);
        f.set(ctx, dummy);
        ctx.setCommand(MysqlCommand.COM_STMT_PREPARE);

        QueryStatement inner = (QueryStatement) UtFrameUtils.parseStmtWithNewParser("select 1", ctx);
        PrepareStmt p = new PrepareStmt(name, inner, null);

        PrepareStmtContext originalCtx = new PrepareStmtContext(p, ctx, null);
        ctx.putPreparedStmt(name, originalCtx);

        // put a placeholder under numeric key to ensure branch not executed
        String key = String.valueOf(computeKey(name));
        PrepareStmtContext placeholder = new PrepareStmtContext(p, ctx, null);
        ctx.putPreparedStmt(key, placeholder);

        StmtExecutor executor = new StmtExecutor(ctx, p);
        Method m = StmtExecutor.class.getDeclaredMethod("sendStmtPrepareOK", PrepareStmt.class);
        m.setAccessible(true);
        m.invoke(executor, p);

        // verify mapping still points to placeholder (not overwritten)
        Assert.assertSame(placeholder, ctx.getPreparedStmt(key));
    }

    private static int computeKey(String name) {
        int stmtId;
        try {
            long longId = Long.parseLong(name);
            if (longId > 0xFFFFFFFFL || longId < 0) {
                stmtId = name.hashCode() & 0x7FFFFFFF;
            } else {
                stmtId = (int) longId;
            }
        } catch (NumberFormatException e) {
            stmtId = name.hashCode() & 0x7FFFFFFF;
        }
        return stmtId;
    }
} 