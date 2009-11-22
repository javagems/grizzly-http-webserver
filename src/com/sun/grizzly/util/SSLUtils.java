/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
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
 */

package com.sun.grizzly.util;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;

/**
 * SSL over NIO utility class. The class handle the SSLEngine operations 
 * needed to support SSL over NIO. 
 *
 * TODO: Create an object that Wrap SSLEngine and its associated buffers.
 *
 * @author Jeanfrancois Arcand
 */
public class SSLUtils {
    
    /**
     * The maximum size a ByteBuffer can take.
     */
    public final static int MAX_BB_SIZE = 48 * 4096;

    
    /*
     * An empty ByteBuffer used for handshaking
     */
    protected final static ByteBuffer hsBB = ByteBuffer.allocate(0);
   
    
    /**
     * The time to wait before timing out when reading bytes
     */
    private static int readTimeout = 30000;    
    
    
    /**
     * Read and decrypt bytes from the underlying SSL connections.
     * @param socketChannel underlying socket channel
     * @param sslEngine{@link SSLEngine}
     * @param byteBuffer buffer for application decrypted data
     * @param inputBB buffer for reading enrypted data from socket
     * @return  number of bytes read
     * @throws java.io.IOException 
     */    
    public static int doSecureRead(SelectableChannel channel, SSLEngine sslEngine,
            ByteBuffer byteBuffer, ByteBuffer inputBB) throws IOException {
        
        int initialPosition = byteBuffer.position();
        int byteRead = 0;
        
        // We need to make sure the unwrap worked properly and we have all
        // the packets properly read. If the SSLEngine fail to unwrap all the 
        // bytes, the byteBuffer will be empty event if some encrypted bytes
        // are available. 
        while (byteBuffer.position() == initialPosition){
            int currentRead = SSLUtils.doRead(channel, inputBB,
                    sslEngine,readTimeout);

            if (currentRead > 0) {
                byteRead += currentRead;
            }
            
            if (currentRead > 0 || inputBB.position() > 0) {
                try{
                    byteBuffer = SSLUtils.unwrapAll(byteBuffer,
                            inputBB, sslEngine);
                    
                    if (currentRead == -1 && 
                            byteBuffer.position() == initialPosition) {
                        // if last read was -1 and unwrap decoded nothing then return -1
                        byteRead = -1;
                        break;
                    }
                } catch (IOException ex){
                    Logger logger = LoggerUtils.getLogger();
                    if ( logger.isLoggable(Level.FINE) )
                        logger.log(Level.FINE,"SSLUtils.unwrapAll",ex);
                    return -1;
                }
            } else if (currentRead == -1) {
                byteRead = -1;
                break;
            } else {
                break;
            }   
        }

        return byteRead;
    }   

    /**
     * Read encrypted bytes using an{@link SSLEngine}.
     * @param channel The {@link SelectableChannel}
     * @param inputBB The byteBuffer to store encrypted bytes
     * @param sslEngine The{@link SSLEngine} uses to manage the 
     *                  SSL operations.
     * @param timeout The Selector.select() timeout value. A value of 0 will
     *                be exectuted as a Selector.selectNow();
     * @return the bytes read.
     */
    public static int doRead(SelectableChannel channel, ByteBuffer inputBB, 
            SSLEngine sslEngine, int timeout) { 
        
        if (channel == null) return -1;

        try {
            int bytesRead = Utils.readWithTemporarySelector(channel, 
                    inputBB, timeout);
            
            if (bytesRead == -1) {
                try {
                    sslEngine.closeInbound();
                } catch (IOException ex) {
                }
            }

            return bytesRead;
        } catch (Throwable t){
            Logger logger = LoggerUtils.getLogger();
            if ( logger.isLoggable(Level.FINEST) ){
                logger.log(Level.FINEST,"doRead",t);
            }            
            return -1;
        }
    } 
    
    
    /**
     * Unwrap all encrypted bytes from <code>inputBB</code> to 
     * {@link ByteBuffer} using the{@link SSLEngine}
     * @param byteBuffer the decrypted ByteBuffer
     * @param inputBB the encrypted ByteBuffer
     * @param sslEngine The SSLEngine used to manage the SSL operations.
     * @return the decrypted ByteBuffer
     * @throws java.io.IOException 
     */
    public static ByteBuffer unwrapAll(ByteBuffer byteBuffer, 
            ByteBuffer inputBB, SSLEngine sslEngine) throws IOException{
        
        SSLEngineResult result = null;
        do{
            try{
               result = unwrap(byteBuffer,inputBB,sslEngine);
            } catch (Throwable ex){
                Logger logger = LoggerUtils.getLogger();
                if ( logger.isLoggable(Level.FINE) ){
                    logger.log(Level.FINE,"unwrap",ex);
                }
                inputBB.compact();
            }

            if (result != null){
                switch (result.getStatus()) {

                    case BUFFER_UNDERFLOW:
                    case CLOSED:
                        // Closed or need more data.
                        break;
                    case OK:
                        if (result.getHandshakeStatus() 
                                == HandshakeStatus.NEED_TASK) {
                            executeDelegatedTask(sslEngine);
                        }
                        break;
                    case BUFFER_OVERFLOW:
                         byteBuffer = reallocate(byteBuffer);
                         break;
                    default:                       
                        throw new 
                             IOException("Unwrap error: "+ result.getStatus());
                 }   
             }
        } while (inputBB.position() > 0 && result!= null &&
                result.getStatus() != Status.BUFFER_UNDERFLOW &&
                result.getStatus() != Status.CLOSED);
        return byteBuffer;
    }
    
    
    /**
     * Unwrap available encrypted bytes from <code>inputBB</code> to 
     * {@link ByteBuffer} using the{@link SSLEngine}
     * @param byteBuffer the decrypted ByteBuffer
     * @param inputBB the encrypted ByteBuffer
     * @param sslEngine The SSLEngine used to manage the SSL operations.
     * @return SSLEngineResult of the SSLEngine.unwrap operation.
     * @throws java.io.IOException 
     */
    public static SSLEngineResult unwrap(ByteBuffer byteBuffer, 
            ByteBuffer inputBB, SSLEngine sslEngine) throws IOException{

        // Logging block
        if (LoggerUtils.getLogger().isLoggable(Level.FINE)) {
            LoggerUtils.getLogger().log(Level.FINE, "start unwrap. engine: " + 
                    sslEngine + " buffer: " +
                    byteBuffer + " secured: " + inputBB);
            if (LoggerUtils.getLogger().isLoggable(Level.FINER)) {
                StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
                StringBuilder buffer = new StringBuilder();
                for (StackTraceElement element : stackTraceElements) {
                    buffer.append('\n');
                    buffer.append(element);
                }

                LoggerUtils.getLogger().log(Level.FINER, buffer.toString());
            }
        }

        inputBB.flip();        
        SSLEngineResult result = sslEngine.unwrap(inputBB, byteBuffer);
        inputBB.compact();
        
        // Logging block
        if (LoggerUtils.getLogger().isLoggable(Level.FINE)) {
            int bytesProduced = result.bytesProduced();
            LoggerUtils.getLogger().log(Level.FINE, "after unwrap. engine: " +
                    sslEngine + " buffer: " +
                    byteBuffer + " secured: " + inputBB + " consumed: " + 
                    result.bytesConsumed() + " produced: " + bytesProduced + 
                    " status: " + result.getStatus() +
                    " handshakeStatus: " + result.getHandshakeStatus());
            if (bytesProduced > 0 && LoggerUtils.getLogger().isLoggable(Level.FINER)) {
                byteBuffer.position(byteBuffer.position() - bytesProduced);
                byte[] producedBytes = new byte[bytesProduced];
                byteBuffer.get(producedBytes);
                LoggerUtils.getLogger().log(Level.FINER, new String(producedBytes));
            }
        }
        
        return result;
    }
    
    
    /**
     * Encrypt bytes.
     * @param byteBuffer the decrypted ByteBuffer
     * @param outputBB the encrypted ByteBuffer
     * @param sslEngine The SSLEngine used to manage the SSL operations.
     * @return SSLEngineResult of the SSLEngine.wrap operation.
     * @throws java.io.IOException 
     */
    public static SSLEngineResult wrap(ByteBuffer byteBuffer,
            ByteBuffer outputBB, SSLEngine sslEngine) throws IOException {        
        
        outputBB.clear();   
        SSLEngineResult result = sslEngine.wrap(byteBuffer, outputBB);
        outputBB.flip();
        return result;
    }
    
    
    /**
     * Resize a ByteBuffer.
     * @param byteBuffer  {@link ByteBuffer} to re-allocate
     * @return  {@link ByteBuffer} reallocted
     * @throws java.io.IOException 
     */
    private static ByteBuffer reallocate(ByteBuffer byteBuffer) 
            throws IOException{
        
        if (byteBuffer.capacity() > MAX_BB_SIZE){
            throw new IOException("Unwrap error: BUFFER_OVERFLOW");
        }
        ByteBuffer tmp = ByteBuffer.allocate(byteBuffer.capacity() * 2);
        byteBuffer.flip();
        tmp.put(byteBuffer);
        byteBuffer = tmp;
        return byteBuffer;
    }
    
     
    /**
     * Complete hanshakes operations.
     * @param sslEngine The SSLEngine used to manage the SSL operations.
     * @return SSLEngineResult.HandshakeStatus
     */
    public static SSLEngineResult.HandshakeStatus 
            executeDelegatedTask(SSLEngine sslEngine) {

        Runnable runnable;
        while ((runnable = sslEngine.getDelegatedTask()) != null) {
            runnable.run();
        }
        return sslEngine.getHandshakeStatus();
    }
    
    
    /**
     * Perform an SSL handshake using the SSLEngine. 
     * Note: If handshake was done successfully - outputBB will be cleared out,
     *       but this is *not* ready data to be written.
     * 
     * @param channel the {@link SelectableChannel}
     * @param byteBuffer The application {@link ByteBuffer}
     * @param inputBB The encrypted input {@link ByteBuffer}
     * @param outputBB The encrypted output {@link ByteBuffer}
     * @param sslEngine The SSLEngine used.
     * @param handshakeStatus The current handshake status
     * @return byteBuffer the new ByteBuffer
     * @throws java.io.IOException 
     * @throw IOException if the handshake fail.
     */
    public static ByteBuffer doHandshake(SelectableChannel channel,
            ByteBuffer byteBuffer, ByteBuffer inputBB, ByteBuffer outputBB,
            SSLEngine sslEngine, HandshakeStatus handshakeStatus) 
            throws IOException {
        return doHandshake(channel, byteBuffer, inputBB, outputBB,
                sslEngine, handshakeStatus, readTimeout);
    }

    
    /**
     * Perform an SSL handshake using the SSLEngine. 
     * Note: If handshake was done successfully - outputBB will be cleared out,
     *       but this is *not* ready data to be written.
     * 
     * @param channel the {@link SelectableChannel}
     * @param byteBuffer The application {@link ByteBuffer}
     * @param inputBB The encrypted input {@link ByteBuffer}
     * @param outputBB The encrypted output {@link ByteBuffer}
     * @param sslEngine The SSLEngine used.
     * @param handshakeStatus The current handshake status
     * @param timeout 
     * @return byteBuffer the new ByteBuffer
     * @throws java.io.IOException 
     * @throws IOException if the handshake fail.
     */
    public static ByteBuffer doHandshake(SelectableChannel channel,
            ByteBuffer byteBuffer, ByteBuffer inputBB, ByteBuffer outputBB,
            SSLEngine sslEngine, HandshakeStatus handshakeStatus,int timeout) 
            throws IOException {
        return doHandshake(channel, byteBuffer, inputBB, outputBB,
                sslEngine, handshakeStatus, timeout, inputBB.position() > 0);
    }

    /**
     * Perform an SSL handshake using the SSLEngine.
     * Note: If handshake was done successfully - outputBB will be cleared out,
     *       but this is *not* ready data to be written.
     * 
     * @param channel the {@link SelectableChannel}
     * @param byteBuffer The application {@link ByteBuffer}
     * @param inputBB The encrypted input {@link ByteBuffer}
     * @param outputBB The encrypted output {@link ByteBuffer}
     * @param sslEngine The SSLEngine used.
     * @param handshakeStatus The current handshake status
     * @param timeout 
     * @param useReadyBuffer does method need to read data before UNWRAP or use
     *                       a data from inputBB
     * @return byteBuffer the new ByteBuffer
     * @throws java.io.IOException 
     * @throws IOException if the handshake fail.
     */
    public static ByteBuffer doHandshake(SelectableChannel channel,
            ByteBuffer byteBuffer, ByteBuffer inputBB, ByteBuffer outputBB,
            SSLEngine sslEngine, HandshakeStatus handshakeStatus,
            int timeout,boolean useReadyBuffer)
            throws IOException {
        
        SSLEngineResult result;
        int eof = timeout > 0 ? 0 : -1;
        while (handshakeStatus != HandshakeStatus.FINISHED){
            switch (handshakeStatus) {
               case NEED_UNWRAP:
                    if (!useReadyBuffer) {
                        if (doRead(channel, inputBB, sslEngine, timeout) <= eof) {
                            try {
                                sslEngine.closeInbound();
                            } catch (IOException ex) {
                                Logger logger = LoggerUtils.getLogger();
                                if (logger.isLoggable(Level.FINE)) {
                                    logger.log(Level.FINE, "closeInbound", ex);
                                }
                            }
                            throw new EOFException("Connection closed");
                        }
                    } else {
                        useReadyBuffer = false;
                    }
                    
                    while (handshakeStatus == HandshakeStatus.NEED_UNWRAP) {
                        result = unwrap(byteBuffer,inputBB,sslEngine);
                        handshakeStatus = result.getHandshakeStatus();
                        
                        if (result.getStatus() == Status.BUFFER_UNDERFLOW){
                            break;
                        }
                        
                        switch (result.getStatus()) {
                            case OK:
                                switch (handshakeStatus) {
                                    case NOT_HANDSHAKING:
                                        throw new IOException("No Hanshake");

                                    case NEED_TASK:
                                        handshakeStatus = 
                                                executeDelegatedTask(sslEngine);
                                        break;                               

                                    case FINISHED:
                                       return byteBuffer;
                                }
                                break;
                            case BUFFER_OVERFLOW:
                                byteBuffer = reallocate(byteBuffer);     
                                break;
                            default: 
                                throw new IOException("Handshake exception: " + 
                                        result.getStatus());
                        }
                    }  

                    if (handshakeStatus != HandshakeStatus.NEED_WRAP) {
                        break;
                    }
                case NEED_WRAP:
                    result = wrap(hsBB,outputBB,sslEngine);
                    handshakeStatus = result.getHandshakeStatus();
                    switch (result.getStatus()) {
                        case OK:

                            if (handshakeStatus == HandshakeStatus.NEED_TASK) {
                                handshakeStatus = executeDelegatedTask(sslEngine);
                            }

                            // Flush all Server bytes to the client.
                            if (channel != null) {
                                OutputWriter.flushChannel(
                                        channel, outputBB);
                                outputBB.clear();
                            }
                            break;
                        default: 
                            throw new IOException("Handshaking error: " 
                                    + result.getStatus());
                        }
                        break;
                default: 
                    throw new RuntimeException("Invalid Handshaking State" +
                            handshakeStatus);
            } 
        }
        return byteBuffer;
    }

    
    /**
     * Get the peer certificate list by initiating a new handshake.
     * @param channel {@link SelectableChannel}
     * @param needClientAuth 
     * @return Object[] An array of X509Certificate.
     * @throws java.io.IOException 
     */
    public static Object[] doPeerCertificateChain(SelectableChannel channel,
            ByteBuffer byteBuffer, ByteBuffer inputBB, ByteBuffer outputBB,
            SSLEngine sslEngine, boolean needClientAuth, int timeout) throws IOException {
        
        Logger logger = LoggerUtils.getLogger();
    
        Certificate[] certs=null;
        try {
            certs = sslEngine.getSession().getPeerCertificates();
        } catch( Throwable t ) {
            if ( logger.isLoggable(Level.FINE))
                logger.log(Level.FINE,"Error getting client certs",t);
        }
 
        if (certs == null && needClientAuth){
            sslEngine.getSession().invalidate();
            sslEngine.setNeedClientAuth(true);
            sslEngine.beginHandshake();         
                      
            ByteBuffer origBB = byteBuffer;
            // In case the application hasn't read all the body bytes.
            if ( origBB.position() != origBB.limit() ){
                byteBuffer = ByteBuffer.allocate(origBB.capacity());
            } else {
                byteBuffer.clear();
            }
            outputBB.position(0);
            outputBB.limit(0); 
            
            // We invalidate ssl seesion, so no need for unwrap
            try{
                doHandshake(channel, byteBuffer, inputBB, outputBB, 
                        sslEngine, HandshakeStatus.NEED_WRAP, timeout);
            } catch (Throwable ex){
                if ( logger.isLoggable(Level.FINE))
                    logger.log(Level.FINE,"Error during handshake",ex);   
                return null;
            } finally {
                byteBuffer = origBB;
                byteBuffer.clear();
            }            
            
            try {
                certs = sslEngine.getSession().getPeerCertificates();
            } catch( Throwable t ) {
                if ( logger.isLoggable(Level.FINE))
                    logger.log(Level.FINE,"Error getting client certs",t);
            }
        }
        
        if( certs==null ) return null;
        
        X509Certificate[] x509Certs = new X509Certificate[certs.length];
        for(int i=0; i < certs.length; i++) {
            if( certs[i] instanceof X509Certificate ) {
                x509Certs[i] = (X509Certificate)certs[i];
            } else {
                try {
                    byte [] buffer = certs[i].getEncoded();
                    CertificateFactory cf =
                    CertificateFactory.getInstance("X.509");
                    ByteArrayInputStream stream = new ByteArrayInputStream(buffer);
                    x509Certs[i] = (X509Certificate)
                    cf.generateCertificate(stream);
                } catch(Exception ex) { 
                    logger.log(Level.INFO,"Error translating cert " + certs[i],
                                     ex);
                    return null;
                }
            }
            
            if(logger.isLoggable(Level.FINE))
                logger.log(Level.FINE,"Cert #" + i + " = " + x509Certs[i]);
        }
        
        if(x509Certs.length < 1)
            return null;
            
        return x509Certs;
    }    
    
    
    /**
     * Allocate themandatory {@link ByteBuffer}s. Since the ByteBuffer
     * are maintaned on the {@link WorkerThread} lazily, this method
     * makes sure the ByteBuffers are properly allocated and configured.
     */    
    public static void allocateThreadBuffers(int defaultBufferSize) {
        final WorkerThread workerThread = 
                (WorkerThread)Thread.currentThread();
        ByteBuffer byteBuffer = workerThread.getByteBuffer();
        ByteBuffer outputBB = workerThread.getOutputBB();
        ByteBuffer inputBB = workerThread.getInputBB();
            
        int expectedSize = workerThread.getSSLEngine().getSession()
            .getPacketBufferSize();
        if (defaultBufferSize < expectedSize){
            defaultBufferSize = expectedSize;
        }

        if (inputBB != null && inputBB.capacity() < defaultBufferSize) {
            ByteBuffer newBB = ByteBuffer.allocate(defaultBufferSize);
            inputBB.flip();
            newBB.put(inputBB);
            inputBB = newBB;                                
        } else if (inputBB == null){
            inputBB = ByteBuffer.allocate(defaultBufferSize);
        }      
        
        if (outputBB == null) {
            outputBB = ByteBuffer.allocate(defaultBufferSize);
        } 
        
        if (byteBuffer == null){
            byteBuffer = ByteBuffer.allocate(defaultBufferSize * 2);
        } 

        expectedSize = workerThread.getSSLEngine().getSession()
            .getApplicationBufferSize();
        if ( expectedSize > byteBuffer.capacity() ) {
            ByteBuffer newBB = ByteBuffer.allocate(expectedSize);
            byteBuffer.flip();
            newBB.put(byteBuffer);
            byteBuffer = newBB;
        }   

        workerThread.setInputBB(inputBB);
        workerThread.setOutputBB(outputBB);  
        workerThread.setByteBuffer(byteBuffer);
   
        outputBB.position(0);
        outputBB.limit(0);
    }


    public static int getReadTimeout() {
        return readTimeout;
    }

    
    public static void setReadTimeout(int aReadTimeout) {
        readTimeout = aReadTimeout;
    }

}
