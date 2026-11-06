/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http2;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for Section 5.2 of
 * <a href="https://tools.ietf.org/html/rfc7540">RFC 7540</a>.
 * <br>
 * The order of tests in this class is aligned with the order of the
 * requirements in the RFC.
 */
public class TestHttp2Section_5_2 extends Http2TestBase {

    /*
     * Get the connection to a point where 1k of 8k response body has been
     * read and the flow control for the stream has no capacity left.
     */
    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        http2Connect();

        // This test uses small window updates that will trigger the excessive
        // overhead protection so disable it.
        http2Protocol.setOverheadWindowUpdateThreshold(0);

        // Set the default window size to 1024 bytes
        sendSettings(0, false, new SettingValue(4, 1024));
        // Wait for the ack
        parser.readFrame(true);
        output.clearTrace();

        // Headers + 8k response
        sendSimpleGetRequest(3);

        // Headers
        parser.readFrame(true);
        // First 1k of body
        parser.readFrame(true);
        output.clearTrace();
    }


    @Test
    public void testFlowControlLimits01() throws Exception {
        readBytes(20);
        clearRemainder();
    }


    @Test
    public void testFlowControlLimits02() throws Exception {
        readBytes(1);
        readBytes(1);
        readBytes(1024);
        readBytes(1);
        clearRemainder();
    }


    @Test
    public void testFlowControlLimits03() throws Exception {
        readBytes(8192,7168);
    }


    @Test
    public void testFlowControlLimits04() throws Exception {
        readBytes(7168, 7168, true);
    }


    private void readBytes(int len) throws Exception {
        readBytes(len, len);
    }


    private void readBytes(int len, int expected) throws Exception {
        readBytes(len, expected, len > expected);
    }


    private void readBytes(int len, int expected, boolean eos) throws Exception {
        sendWindowUpdate(3, len);
        parser.readFrame(true);
        String expectedTrace = "3-Body-" + expected + "\n";
        if (eos) {
            expectedTrace += "3-EndOfStream\n";
        }
        Assert.assertEquals(expectedTrace, output.getTrace());
        output.clearTrace();
    }


    private void clearRemainder() throws Exception {
        // Remainder
        sendWindowUpdate(3, 8192);
        parser.readFrame(true);
    }


    @Test
    public void testFlowControlAndStreamReset() throws Exception {
        // At start of test Stream 3 has 1k of 8k written and no capacity left

        // Empty the connection window (64k - 1 initially, 8k used by stream 1)
        sendWindowUpdate(3, 7 * 1024);
        parser.readFrame(true);

        sendWindowUpdate(0, 1);

        for (int i = 5; i < 17; i += 2) {
            sendSimpleGetRequest(i);
            parser.readFrame(true);
            parser.readFrame(true);
            sendWindowUpdate(i, 7 * 1024);
            parser.readFrame(true);
        }

        output.clearTrace();

        // Connection flow control window is now empty

        // Put a stream on the backlog and then cancel it
        sendSimpleGetRequest(17);
        // Read headers. The response body write is now waiting in the backlog.
        parser.readFrame(true);
        // Give the response body write time to be added to the backlog
        Thread.sleep(500);
        sendRst(17, Http2Error.NO_ERROR.getCode());

        // Increase default window size to 8k
        sendSettings(0, false, new SettingValue(4, 8 * 1024));
        // Read any reset from the server for stream 17 and the settings ACK
        while (!output.getTrace().endsWith("0-Settings-Ack\n")) {
            parser.readFrame(true);
        }

        sendSimpleGetRequest(19);
        // Read headers
        parser.readFrame(true);

        // Clear trace as what happens from this point is of primary interest in this test
        output.clearTrace();

        // This should release the entire body for stream 19
        sendWindowUpdate(0, 8 * 1024);

        parser.readFrame(true);
        Assert.assertEquals("19-Body-8192\n19-EndOfStream\n", output.getTrace());
        output.clearTrace();

        /*
         * The connection window is empty again. If the reset stream's
         * allocation request (1k) leaked into the backlog, a 1k increment
         * cannot clear the backlog and is offered to streams that are no longer
         * in the priority tree, so the next stream never receives it.
         */
        sendWindowUpdate(0, 1024);
        sendSimpleGetRequest(21);
        // Headers
        parser.readFrame(true);
        // Body
        parser.readFrame(true);
        Assert.assertEquals("21-HeadersStart\n21-Header-[:status]-[200]\n" +
                "21-Header-[content-type]-[application/octet-stream]\n" +
                "21-Header-[content-length]-[8192]\n" +
                "21-Header-[date]-[Wed, 11 Nov 2015 19:18:42 GMT]\n" +
                "21-HeadersEnd\n" +
                "21-Body-1024\n", output.getTrace());
    }
}
