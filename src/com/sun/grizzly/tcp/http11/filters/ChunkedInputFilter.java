/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sun.grizzly.tcp.http11.filters;

import com.sun.grizzly.tcp.InputBuffer;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.http11.Constants;
import com.sun.grizzly.tcp.http11.InputFilter;
import com.sun.grizzly.util.buf.ByteChunk;
import com.sun.grizzly.util.buf.HexUtils;
import java.io.IOException;


/**
 * Chunked input filter. Parses chunked data according to
 * <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1">http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.6.1</a><br>
 * 
 * @author Remy Maucherat
 * @author Filip Hanik
 */
public class ChunkedInputFilter implements InputFilter {


    // -------------------------------------------------------------- Constants


    protected static final String ENCODING_NAME = "chunked";
    protected static final ByteChunk ENCODING = new ByteChunk();


    // ----------------------------------------------------- Static Initializer


    static {
        ENCODING.setBytes(ENCODING_NAME.getBytes(), 0, ENCODING_NAME.length());
    }


    // ----------------------------------------------------- Instance Variables


    /**
     * Next buffer in the pipeline.
     */
    protected InputBuffer buffer;


    /**
     * Number of bytes remaining in the current chunk.
     */
    protected int remaining = 0;


    /**
     * Position in the buffer.
     */
    protected int pos = 0;


    /**
     * Last valid byte in the buffer.
     */
    protected int lastValid = 0;


    /**
     * Read bytes buffer.
     */
    protected byte[] buf = null;


    /**
     * Byte chunk used to read bytes.
     */
    protected ByteChunk readChunk = new ByteChunk();


    /**
     * Flag set to true when the end chunk has been read.
     */
    protected boolean endChunk = false;

    /**
     * Flag set to true if the next call to doRead() must parse a CRLF pair
     * before doing anything else.
     */
    protected boolean needCRLFParse = false;

    // ------------------------------------------------------------- Properties


    // ---------------------------------------------------- InputBuffer Methods


    /**
     * Read bytes.
     * 
     * @return If the filter does request length control, this value is
     * significant; it should be the number of bytes consumed from the buffer,
     * up until the end of the current request body, or the buffer length, 
     * whichever is greater. If the filter does not do request body length
     * control, the returned value should be -1.
     */
    public int doRead(ByteChunk chunk, Request req)
        throws IOException {

        if (endChunk)
            return -1;

        if(needCRLFParse) {
            needCRLFParse = false;
            parseCRLF();
        }

        if (remaining <= 0) {
            if (!parseChunkHeader()) {
                throw new IOException("Invalid chunk header");
            }
            if (endChunk) {
                parseEndChunk();
                return -1;
            }
        }

        int result = 0;

        if (pos >= lastValid) {
            readBytes();
        }

        if (remaining > (lastValid - pos)) {
            result = lastValid - pos;
            remaining = remaining - result;
            chunk.setBytes(buf, pos, result);
            pos = lastValid;
        } else {
            result = remaining;
            chunk.setBytes(buf, pos, remaining);
            pos = pos + remaining;
            remaining = 0;
            //we need a CRLF
            if ((pos+1) >= lastValid) {   
                //if we call parseCRLF we overrun the buffer here
                //so we defer it to the next call BZ 11117
                needCRLFParse = true;
            } else {
                parseCRLF(); //parse the CRLF immediately
            }
        }

        return result;

    }


    // ---------------------------------------------------- InputFilter Methods


    /**
     * Read the content length from the request.
     */
    public void setRequest(Request request) {
    }


    /**
     * End the current request.
     */
    public long end()
        throws IOException {

        // Consume extra bytes : parse the stream until the end chunk is found
        while (doRead(readChunk, null) >= 0) {
        }

        // Return the number of extra bytes which were consumed
        return (lastValid - pos);

    }


    /**
     * Amount of bytes still available in a buffer.
     */
    public int available() {
        return (lastValid - pos);
    }
    

    /**
     * Set the next buffer in the filter pipeline.
     */
    public void setBuffer(InputBuffer buffer) {
        this.buffer = buffer;
    }


    /**
     * Make the filter ready to process the next request.
     */
    public void recycle() {
        remaining = 0;
        pos = 0;
        lastValid = 0;
        endChunk = false;
    }


    /**
     * Return the name of the associated encoding; Here, the value is 
     * "identity".
     */
    public ByteChunk getEncodingName() {
        return ENCODING;
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Read bytes from the previous buffer.
     */
    protected int readBytes()
        throws IOException {

        int nRead = buffer.doRead(readChunk, null);
        pos = readChunk.getStart();
        lastValid = pos + nRead;
        buf = readChunk.getBytes();

        return nRead;

    }


    /**
     * Parse the header of a chunk.
     * A chunk header can look like 
     * A10CRLF
     * F23;chunk-extension to be ignoredCRLF
     * The letters before CRLF but after the trailer mark, must be valid hex digits, 
     * we should not parse F23IAMGONNAMESSTHISUP34CRLF as a valid header
     * according to spec
     */
    protected boolean parseChunkHeader()
        throws IOException {

        int result = 0;
        boolean eol = false;
        boolean readDigit = false;
        boolean trailer = false;

        while (!eol) {

            if (pos >= lastValid) {
                if (readBytes() <= 0)
                    return false;
            }

            if (buf[pos] == Constants.CR) {
            } else if (buf[pos] == Constants.LF) {
                eol = true;
            } else if (buf[pos] == Constants.SEMI_COLON) {
                trailer = true;
            } else if (!trailer) { 
                //don't read data after the trailer
                if (HexUtils.DEC[buf[pos]] != -1) {
                    readDigit = true;
                    result *= 16;
                    result += HexUtils.DEC[buf[pos]];
                } else {
                    //we shouldn't allow invalid, non hex characters
                    //in the chunked header
                    return false;
                }
            }

            pos++;

        }

        if (!readDigit)
            return false;

        if (result == 0)
            endChunk = true;

        remaining = result;
        if (remaining < 0)
            return false;

        return true;

    }


    /**
     * Parse CRLF at end of chunk.
     */
    protected boolean parseCRLF()
        throws IOException {

        boolean eol = false;
        boolean crfound = false;

        while (!eol) {

            if (pos >= lastValid) {
                if (readBytes() <= 0)
                    throw new IOException("Invalid CRLF");
            }

            if (buf[pos] == Constants.CR) {
                if (crfound) throw new IOException("Invalid CRLF, two CR characters encountered.");
                crfound = true;
            } else if (buf[pos] == Constants.LF) {
                if (!crfound) throw new IOException("Invalid CRLF, no CR character encountered.");
                eol = true;
            } else {
                throw new IOException("Invalid CRLF");
            }

            pos++;

        }

        return true;

    }


    /**
     * Parse end chunk data.
     * FIXME: Handle trailers
     */
    protected boolean parseEndChunk()
        throws IOException {

        return parseCRLF(); // FIXME

    }


}
