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
package com.sun.grizzly.util.buf;

import com.sun.grizzly.util.LoggerUtils;
import java.io.CharConversionException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 
 *  All URL decoding happens here. This way we can reuse, review, optimize
 *  without adding complexity to the buffers.
 *
 *  The conversion will modify the original buffer.
 * 
 *  @author Costin Manolache
 */
public final class UDecoder {

    /**
     * Default Logger.
     */
    private final static Logger logger = LoggerUtils.getLogger();
    protected static final boolean ALLOW_ENCODED_SLASH =
            Boolean.valueOf(System.getProperty("com.sun.grizzly.util.buf.UDecoder.ALLOW_ENCODED_SLASH", "false")).booleanValue();

    private boolean allowEncodedSlash;
    
    public UDecoder(){
        this(ALLOW_ENCODED_SLASH);
    }
    
    public UDecoder(boolean allowEncodedSlash){
        this.allowEncodedSlash = allowEncodedSlash;
    }
    
    /** URLDecode, will modify the source.  Includes converting
     *  '+' to ' '.
     */
    public void convert(ByteChunk mb)
            throws IOException {
        convert(mb, true);
    }

    
    /** 
     * URLDecode the {@link ByteChunk}
     */
    public void convert(ByteChunk mb, boolean query) throws IOException {
        convert(mb,query,ALLOW_ENCODED_SLASH);
    }
    
    /** 
     * URLDecode the {@link ByteChunk}
     */
    public void convert(ByteChunk mb, boolean query, boolean allowEncodedSlash)
            throws IOException {
        int start = mb.getOffset();
        byte buff[] = mb.getBytes();
        int end = mb.getEnd();

        int idx = ByteChunk.indexOf(buff, start, end, '%');
        int idx2 = -1;
        if (query) {
            idx2 = ByteChunk.indexOf(buff, start, end, '+');
        }
        if (idx < 0 && idx2 < 0) {
            return;
        }

        // idx will be the smallest positive inxes ( first % or + )
        if (idx2 >= 0 && idx2 < idx) {
            idx = idx2;
        }
        if (idx < 0) {
            idx = idx2;
        }

        boolean noSlash = !(allowEncodedSlash || query);

        for (int j = idx; j < end; j++, idx++) {
            if (buff[j] == '+' && query) {
                buff[idx] = (byte) ' ';
            } else if (buff[j] != '%') {
                buff[idx] = buff[j];
            } else {
                // read next 2 digits
                if (j + 2 >= end) {
                    throw new CharConversionException("EOF");
                }
                byte b1 = buff[j + 1];
                byte b2 = buff[j + 2];
                if (!isHexDigit(b1) || !isHexDigit(b2)) {
                    throw new CharConversionException("isHexDigit");
                }

                j += 2;
                int res = x2c(b1, b2);
                if (noSlash && (res == '/')) {
                    throw new CharConversionException("noSlash");
                }
                buff[idx] = (byte) res;
            }
        }

        mb.setEnd(idx);

        return;
    }

    // -------------------- Additional methods --------------------
    // XXX What do we do about charset ????
    /** In-buffer processing - the buffer will be modified
     *  Includes converting  '+' to ' '.
     */
    public void convert(CharChunk mb)
            throws IOException {
        convert(mb, true);
    }

    /** In-buffer processing - the buffer will be modified
     */
    public void convert(CharChunk mb, boolean query)
            throws IOException {
        //	log( "Converting a char chunk ");
        int start = mb.getOffset();
        char buff[] = mb.getBuffer();
        int cend = mb.getEnd();

        int idx = CharChunk.indexOf(buff, start, cend, '%');
        int idx2 = -1;
        if (query) {
            idx2 = CharChunk.indexOf(buff, start, cend, '+');
        }
        if (idx < 0 && idx2 < 0) {
            return;
        }

        if (idx2 >= 0 && idx2 < idx) {
            idx = idx2;
        }
        if (idx < 0) {
            idx = idx2;
        }

        for (int j = idx; j < cend; j++, idx++) {
            if (buff[j] == '+' && query) {
                buff[idx] = (' ');
            } else if (buff[j] != '%') {
                buff[idx] = buff[j];
            } else {
                // read next 2 digits
                if (j + 2 >= cend) {
                    // invalid
                    throw new CharConversionException("EOF");
                }
                char b1 = buff[j + 1];
                char b2 = buff[j + 2];
                if (!isHexDigit(b1) || !isHexDigit(b2)) {
                    throw new CharConversionException("isHexDigit");
                }

                j += 2;
                int res = x2c(b1, b2);
                buff[idx] = (char) res;
            }
        }
        mb.setEnd(idx);
    }

    /** URLDecode, will modify the source
     *  Includes converting  '+' to ' '.
     */
    public void convert(MessageBytes mb)
            throws IOException {
        convert(mb, true);
    }
    
    /** 
     * URLDecode, will modify the source
     */
    public void convert(MessageBytes mb, boolean query) throws IOException{
        convert(mb,query,ALLOW_ENCODED_SLASH);
    }
    
    /** URLDecode, will modify the source
     */
    public void convert(MessageBytes mb, boolean query, boolean allowEncodingSlash)
            throws IOException {

        switch (mb.getType()) {
            case MessageBytes.T_STR:
                String strValue = mb.toString();
                if (strValue == null) {
                    return;
                }
                mb.setString(convert(strValue, query));
                break;
            case MessageBytes.T_CHARS:
                CharChunk charC = mb.getCharChunk();
                convert(charC, query);
                break;
            case MessageBytes.T_BYTES:
                ByteChunk bytesC = mb.getByteChunk();
                convert(bytesC, query,allowEncodingSlash);
                break;
        }
    }

    // XXX Old code, needs to be replaced !!!!
    // 
    public final String convert(String str) {
        return convert(str, true);
    }

    public final String convert(String str, boolean query) {
        if (str == null) {
            return null;
        }

        if ((!query || str.indexOf('+') < 0) && str.indexOf('%') < 0) {
            return str;
        }

        StringBuilder dec = new StringBuilder();    // decoded string output
        int strPos = 0;
        int strLen = str.length();

        dec.ensureCapacity(str.length());
        while (strPos < strLen) {
            int laPos;        // lookahead position

            // look ahead to next URLencoded metacharacter, if any
            for (laPos = strPos; laPos < strLen; laPos++) {
                char laChar = str.charAt(laPos);
                if ((laChar == '+' && query) || (laChar == '%')) {
                    break;
                }
            }

            // if there were non-metacharacters, copy them all as a block
            if (laPos > strPos) {
                dec.append(str.substring(strPos, laPos));
                strPos = laPos;
            }

            // shortcut out of here if we're at the end of the string
            if (strPos >= strLen) {
                break;
            }

            // process next metacharacter
            char metaChar = str.charAt(strPos);
            if (metaChar == '+') {
                dec.append(' ');
                strPos++;
                continue;
            } else if (metaChar == '%') {
                // We throw the original exception - the super will deal with
                // it
                //                try {
                dec.append((char) Integer.parseInt(str.substring(strPos + 1, strPos + 3), 16));
                strPos += 3;
            }
        }

        return dec.toString();
    }

    private static boolean isHexDigit(int c) {
        return ((c >= '0' && c <= '9') ||
                (c >= 'a' && c <= 'f') ||
                (c >= 'A' && c <= 'F'));
    }

    private static int x2c(byte b1, byte b2) {
        int digit = (b1 >= 'A') ? ((b1 & 0xDF) - 'A') + 10 : (b1 - '0');
        digit *= 16;
        digit += (b2 >= 'A') ? ((b2 & 0xDF) - 'A') + 10 : (b2 - '0');
        return digit;
    }

    private static int x2c(char b1, char b2) {
        int digit = (b1 >= 'A') ? ((b1 & 0xDF) - 'A') + 10 : (b1 - '0');
        digit *= 16;
        digit += (b2 >= 'A') ? ((b2 & 0xDF) - 'A') + 10 : (b2 - '0');
        return digit;
    }
    private final static int debug = 0;

    private static void log(String s) {
        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "URLDecoder: " + s);
        }
    }

    public boolean isAllowEncodedSlash() {
        return allowEncodedSlash;
    }

    public void setAllowEncodedSlash(boolean allowEncodedSlash) {
        if (ALLOW_ENCODED_SLASH){
            throw new IllegalStateException("Cannot change the allowedEncodedSlash. " +
                    "Remove the -D property");
        }
        this.allowEncodedSlash = allowEncodedSlash;
    }
}
