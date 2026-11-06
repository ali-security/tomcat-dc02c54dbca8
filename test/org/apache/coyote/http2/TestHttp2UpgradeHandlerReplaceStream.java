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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Assert;
import org.junit.Test;

import org.apache.coyote.Request;

/*
 * Header (trailer) fields received for an HTTP/2 stream that has been closed
 * and replaced must never be emitted to the replaced Stream.
 */
public class TestHttp2UpgradeHandlerReplaceStream {

    private static final String TRAILER_NAME = "x-trailer";
    private static final String TRAILER_VALUE = "leaked-from-stream-3";


    /*
     * Control test. Ensures the trailer block used in the other test is
     * delivered to an active stream so the "no leak" assertion in that test is
     * meaningful.
     */
    @Test
    public void testTrailerDeliveredToActiveStream() throws Exception {
        Http2UpgradeHandler handler = createHandler();

        startHeaders(handler, 3, false);
        Stream stream = (Stream) handler.getHpackDecoder().getHeaderEmitter();
        Assert.assertNotNull(stream);
        Request request = stream.getCoyoteRequest();
        // Start of trailer headers
        startHeaders(handler, 3, true);

        handler.getHpackDecoder().decode(createTrailerBlock());

        Assert.assertEquals(TRAILER_VALUE, request.getTrailerFields().get(TRAILER_NAME));
    }


    /*
     * The stream is recycled (replaced) after the start of the trailer headers
     * but before the remaining trailer headers (e.g. in CONTINUATION frames)
     * have been decoded.
     */
    @Test
    public void testReplaceStreamClearsHeaderEmitter() throws Exception {
        Http2UpgradeHandler handler = createHandler();

        startHeaders(handler, 3, false);
        Stream stream = (Stream) handler.getHpackDecoder().getHeaderEmitter();
        Assert.assertNotNull(stream);
        Request request = stream.getCoyoteRequest();
        // Start of trailer headers
        startHeaders(handler, 3, true);
        Assert.assertSame(stream, handler.getHpackDecoder().getHeaderEmitter());

        // Container thread completes processing of the stream
        stream.recycle();

        Assert.assertSame(Http2UpgradeHandler.HEADER_SINK, handler.getHpackDecoder().getHeaderEmitter());

        // Remaining trailer headers arrive
        handler.getHpackDecoder().decode(createTrailerBlock());

        Assert.assertNull(request.getTrailerFields().get(TRAILER_NAME));
        Assert.assertNull(request.getMimeHeaders().getHeader(TRAILER_NAME));
    }


    private static Http2UpgradeHandler createHandler() {
        Http2Protocol protocol = new Http2Protocol();
        protocol.setAllowedTrailerHeaders(TRAILER_NAME);
        return new Http2UpgradeHandler(protocol, null, null);
    }


    /*
     * Same sequence of calls as Http2Parser.readHeadersFrame()
     */
    private static void startHeaders(Http2UpgradeHandler handler, int streamId, boolean endOfStream)
            throws Exception {
        handler.getHpackDecoder().setHeaderEmitter(handler.headersStart(streamId, endOfStream));
    }


    /*
     * HPACK: Literal Header Field without Indexing - New Name (RFC 7541,
     * section 6.2.2) without Huffman encoding.
     */
    private static ByteBuffer createTrailerBlock() {
        byte[] name = TRAILER_NAME.getBytes(StandardCharsets.ISO_8859_1);
        byte[] value = TRAILER_VALUE.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buffer = ByteBuffer.allocate(3 + name.length + value.length);
        buffer.put((byte) 0x00);
        buffer.put((byte) name.length);
        buffer.put(name);
        buffer.put((byte) value.length);
        buffer.put(value);
        buffer.flip();
        return buffer;
    }
}
