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
 

package com.sun.grizzly.tcp.http11;

import com.sun.grizzly.tcp.InputBuffer;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.util.buf.B2CConverter;
import com.sun.grizzly.util.buf.ByteChunk;
import com.sun.grizzly.util.buf.CharChunk;
import com.sun.grizzly.util.res.StringManager;
import java.io.IOException;
import java.io.Reader;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;


/**
 * The buffer used by Tomcat request. This is a derivative of the Tomcat 3.3
 * OutputBuffer, adapted to handle input instead of output. This allows 
 * complete recycling of the facade objects (the ServletInputStream and the
 * BufferedReader).
 *
 * @author Remy Maucherat
 * @author Jean-Francois Arcand
 */
public class GrizzlyInputBuffer extends Reader
    implements ByteChunk.ByteInputChannel, CharChunk.CharInputChannel,
               CharChunk.CharOutputChannel {

    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
        StringManager.getManager("com.sun.grizzly.util.buf.res");


    // -------------------------------------------------------------- Constants


    public static final String DEFAULT_ENCODING = 
        com.sun.grizzly.tcp.Constants.DEFAULT_CHARACTER_ENCODING;
    public static final int DEFAULT_BUFFER_SIZE = 8*1024;
    static final int debug = 0;


    // The buffer can be used for byte[] and char[] reading
    // ( this is needed to support ServletInputStream and BufferedReader )
    public final int INITIAL_STATE = 0;
    public final int CHAR_STATE = 1;
    public final int BYTE_STATE = 2;


    // ----------------------------------------------------- Instance Variables


    /**
     * The byte buffer.
     */
    private ByteChunk bb;


    /**
     * The chunk buffer.
     */
    private CharChunk cb;


    /**
     * State of the output buffer.
     */
    private int state = 0;


    /**
     * Flag which indicates if the input buffer is closed.
     */
    private boolean closed = false;


    /**
     * Encoding to use.
     */
    private String enc;


    /**
     * Encoder is set.
     */
    private boolean gotEnc = false;


    /**
     * List of encoders.
     */
    protected HashMap<String, B2CConverter> encoders =
        new HashMap<String, B2CConverter>();


    /**
     * Current byte to char converter.
     */
    protected B2CConverter conv;


    /**
     * Associated Coyote request.
     */
    private Request coyoteRequest;


    /**
     * Buffer position.
     */
    private int markPos = -1;


    /**
     * Buffer size.
     */
    private int size = -1;


    // ----------------------------------------------------------- Constructors


    /**
     * Default constructor. Allocate the buffer with the default buffer size.
     */
    public GrizzlyInputBuffer() {

        this(DEFAULT_BUFFER_SIZE);

    }


    /**
     * Alternate constructor which allows specifying the initial buffer size.
     * 
     * @param size Buffer size to use
     */   
    public GrizzlyInputBuffer(int size) {
        
        this.size = size;
        bb = new ByteChunk(size);
        bb.setLimit(size);
        bb.setByteInputChannel(this);
    }
    
    
    // START OF SJSAS 6231069
    private void initChar() {
        if (cb != null)
            return;
        cb = new CharChunk(size);
        cb.setLimit(size);
        cb.setOptimizedWrite(false);
        cb.setCharInputChannel(this);
        cb.setCharOutputChannel(this);
    }
    // END OF SJSAS 6231069
    // ------------------------------------------------------------- Properties


    /**
     * Associated Coyote request.
     * 
     * @param coyoteRequest Associated Coyote request
     */
    public void setRequest(Request coyoteRequest) {
	this.coyoteRequest = coyoteRequest;
    }


    /**
     * Get associated Coyote request.
     * 
     * @return the associated Coyote request
     */
    public Request getRequest() {
        return this.coyoteRequest;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Recycle the output buffer.
     */
    public void recycle() {

        state = INITIAL_STATE;

        // START OF SJSAS 6231069
        /*
        // If usage of mark made the buffer too big, reallocate it
        if (cb.getChars().length > size) {
            cb = new CharChunk(size);
            cb.setLimit(size);
            cb.setCharInputChannel(this);
            cb.setCharOutputChannel(this);
        } else {
            cb.recycle();
        }
        */
        cb = null;
        // END OF SJSAS 6231069

        bb.recycle(); 
        markPos = -1;
        closed = false;

        if (conv != null) {
            conv.recycle();
        }

        gotEnc = false;
        enc = null;

    }


    /**
     * Close the input buffer.
     * 
     * @throws IOException An underlying IOException occurred
     */
    public void close()
        throws IOException {
        closed = true;
    }


    public int available()
        throws IOException {
        if (state == BYTE_STATE) {
            return bb.getLength();
        } else if (state == CHAR_STATE) {
            return cb.getLength();
        } else {
            return 0;
        }
    }


    // ------------------------------------------------- Bytes Handling Methods


    /** 
     * Reads new bytes in the byte chunk.
     * 
     * @param buf Byte buffer to be written to the response
     * @param off Offset
     * @param cnt Length
     * 
     * @throws IOException An underlying IOException occurred
     */
    public int realReadBytes(byte cbuf[], int off, int len)
	throws IOException {

        if (closed)
            return -1;
        if (coyoteRequest == null)
            return -1;

        state = BYTE_STATE;

        int result = coyoteRequest.doRead(bb);

        return result;

    }


    public int readByte()
        throws IOException {
        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        return bb.substract();
    }


    public int read(byte[] b, int off, int len)
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        return bb.substract(b, off, len);
    }


    // ------------------------------------------------- Chars Handling Methods


    /**
     * Since the converter will use append, it is possible to get chars to
     * be removed from the buffer for "writing". Since the chars have already
     * been read before, they are ignored. If a mark was set, then the
     * mark is lost.
     */
    public void realWriteChars(char c[], int off, int len) 
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        // START OF SJSAS 6231069
        initChar();
        // END OF SJSAS 6231069
        markPos = -1;
    }


    public void setEncoding(String s) {
        enc = s;
    }


    public int realReadChars(char cbuf[], int off, int len)
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        initChar();

        if (!gotEnc)
            setConverter();

        if (bb.getLength() <= 0) {
            int nRead = realReadBytes(bb.getBytes(), 0, bb.getBytes().length);
            if (nRead < 0) {
                return -1;
            }
        }

        if (markPos == -1) {
            cb.setOffset(0);
            cb.setEnd(0);
        }
        int limit = bb.getLength()+cb.getStart();
        if ( cb.getLimit() < limit )
            cb.setLimit(limit);
        state = CHAR_STATE;
        conv.convert(bb, cb, bb.getLength());
        bb.setOffset(bb.getEnd());

        return cb.getLength();

    }


    @Override
    public int read()
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        // START OF SJSAS 6231069
        initChar();
        // END OF SJSAS 6231069
        return cb.substract();
    }


    @Override
    public int read(char[] cbuf)
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        // START OF SJSAS 6231069
        initChar();
        // END OF SJSAS 6231069
        return read(cbuf, 0, cbuf.length);
    }


    public int read(char[] cbuf, int off, int len)
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        // START OF SJSAS 6231069
	initChar();
        // END OF SJSAS 6231069
        return cb.substract(cbuf, off, len);
    }


    @Override
    public long skip(long n)
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        if (n < 0) {
            throw new IllegalArgumentException();
        }

        // START OF SJSAS 6231069
        initChar();
        // END OF SJSAS 6231069
        long nRead = 0;
        while (nRead < n) {
            if (cb.getLength() >= n) {
                cb.setOffset(cb.getStart() + (int) n);
                nRead = n;
            } else {
                nRead += cb.getLength();
                cb.setOffset(cb.getEnd());
                int toRead = 0;
                if (cb.getChars().length < (n - nRead)) {
                    toRead = cb.getChars().length;
                } else {
                    toRead = (int) (n - nRead);
                }
                int nb = realReadChars(cb.getChars(), 0, toRead);
                if (nb < 0)
                    break;
            }
        }

        return nRead;

    }


    public boolean ready()
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        // START OF SJSAS 6231069
        initChar();
        // END OF SJSAS 6231069
        return (cb.getLength() > 0);
    }


    public boolean markSupported() {
        return true;
    }


    public void mark(int readAheadLimit)
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        // START OF SJSAS 6231069
        initChar();
        // END OF SJSAS 6231069
        if (cb.getLength() <= 0) {
            cb.setOffset(0);
            cb.setEnd(0);
        } else {
            if ((cb.getBuffer().length > (2 * size)) 
                && (cb.getLength()) < (cb.getStart())) {
                System.arraycopy(cb.getBuffer(), cb.getStart(), 
                                 cb.getBuffer(), 0, cb.getLength());
                cb.setEnd(cb.getLength());
                cb.setOffset(0);
            }
        }
        cb.setLimit(cb.getStart() + readAheadLimit + size);
        markPos = cb.getStart();
    }


    public void reset()
        throws IOException {

        if (closed)
            throw new IOException(sm.getString("inputBuffer.streamClosed"));

        if (state == CHAR_STATE) {
            if (markPos < 0) {
                cb.recycle();
                markPos = -1;
                throw new IOException();
            } else {
                cb.setOffset(markPos);
            }
        } else {
            bb.recycle();
        }
    }


    public void checkConverter() 
        throws IOException {

        if (!gotEnc)
            setConverter();

    }


    protected void setConverter()
        throws IOException {

        if (coyoteRequest != null)
            enc = coyoteRequest.getCharacterEncoding();

        gotEnc = true;
        if (enc == null)
            enc = DEFAULT_ENCODING;
        conv = encoders.get(enc);
        if (conv == null) {
            if (System.getSecurityManager() != null){
                try{
                    conv = AccessController.doPrivileged(
                            new PrivilegedExceptionAction<B2CConverter>(){

                                public B2CConverter run() throws IOException{
                                    return new B2CConverter(enc);
                                }

                            }
                    );              
                }catch(PrivilegedActionException ex){
                    Exception e = ex.getException();
                    if (e instanceof IOException)
                        throw (IOException)e; 

                }
            } else {
                conv = new B2CConverter(enc);
            }               
            encoders.put(enc, conv);
        }

    }
}
