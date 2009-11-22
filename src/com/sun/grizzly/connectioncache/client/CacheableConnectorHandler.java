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

package com.sun.grizzly.connectioncache.client;

import com.sun.grizzly.CallbackHandler;
import com.sun.grizzly.CallbackHandlerSelectionKeyAttachment;
import com.sun.grizzly.ConnectorHandler;
import com.sun.grizzly.Context;
import com.sun.grizzly.Controller;
import com.sun.grizzly.Controller.Protocol;
import com.sun.grizzly.IOEvent;
import com.sun.grizzly.SelectorHandler;
import com.sun.grizzly.connectioncache.spi.transport.ContactInfo;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

/**
 * Extended implementation of the DefaultSelectionKeyHandler with
 * ConnectionManagement integrated in it
 *
 * @author Alexey Stashok
 */
public class CacheableConnectorHandler implements ConnectorHandler<SelectorHandler, CallbackHandler>,
        CallbackHandler {
    private SocketAddress targetAddress;
    private Protocol protocol;
    
    private CacheableConnectorHandlerPool parentPool;
    private ConnectorHandler underlyingConnectorHandler;
    private CallbackHandler underlyingCallbackHandler;
    
    private ConnectExecutor connectExecutor;
    
    public CacheableConnectorHandler(CacheableConnectorHandlerPool parentPool) {
        this.parentPool = parentPool;
        connectExecutor = new ConnectExecutor();
    }
    
    void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }
    
    public Protocol protocol() {
        return protocol;
    }
    
    // connect method #1
    public void connect(SocketAddress remoteAddress, CallbackHandler callbackHandler, SelectorHandler selectorHandler) throws IOException {
        connectExecutor.setParameters(remoteAddress, callbackHandler, selectorHandler);
        doConnect(remoteAddress);
    }
    
    // connect method #2
    public void connect(SocketAddress remoteAddress, CallbackHandler callbackHandler) throws IOException {
        connectExecutor.setParameters(remoteAddress, callbackHandler);
        doConnect(remoteAddress);
    }
    
    // connect method #3
    public void connect(SocketAddress remoteAddress) throws IOException {
        connectExecutor.setParameters(remoteAddress);
        doConnect(remoteAddress);
    }
    
    // connect method #4
    public void connect(SocketAddress remoteAddress, SocketAddress localAddress,
            CallbackHandler callbackHandler, SelectorHandler selectorHandler) throws IOException {
        connectExecutor.setParameters(remoteAddress, localAddress, callbackHandler, selectorHandler);
        doConnect(remoteAddress);
    }
    
    // connect method #5
    public void connect(SocketAddress remoteAddress, SocketAddress localAddress, CallbackHandler callbackHandler) throws IOException {
        connectExecutor.setParameters(remoteAddress, localAddress, callbackHandler);
        doConnect(remoteAddress);
    }
    
    // connect method #6
    public void connect(SocketAddress remoteAddress, SocketAddress localAddress) throws IOException {
        connectExecutor.setParameters(remoteAddress, localAddress);
        doConnect(remoteAddress);
    }
    
    
    /**
     * Releases underlying connection, which means it could be reused for writing
     * by other <code>CacheableConnectorHandler</code>, however this 
     * <code>CacheableConnectorHandler</code> will be still interested in getting 
     * expectedResponseCount responses on it.
     * 
     * @param expectedResponseCount number of reponses expected on the connection
     */
    public void release(int expectedResponseCount) {
        parentPool.getOutboundConnectionCache().release(underlyingConnectorHandler, expectedResponseCount);
    }

    /**
     * Notifies connection cache, that response was received. And connection cache
     * could decrease expected response counter by 1
     */
    public void responseReceived() {
        parentPool.getOutboundConnectionCache().responseReceived(underlyingConnectorHandler);
    }

    /**
     * @param targetAddress target connection address
     * @throws java.io.IOException if IO error happened
     */
    private void doConnect(SocketAddress targetAddress) throws IOException {
        this.targetAddress = targetAddress;
        underlyingCallbackHandler = connectExecutor.callbackHandler;
        underlyingConnectorHandler = parentPool.
                getOutboundConnectionCache().get(
                new CacheableConnectorInfo(parentPool, connectExecutor,
                protocol, targetAddress),
                parentPool.getConnectionFinder());
        
        /* check whether NEW connection was created, or taken from cache */
        if (!connectExecutor.wasCalled()) { // if taken from cache
            //if connection is taken from cache - explicitly notify callback handler
            underlyingConnectorHandler.setCallbackHandler(this);
            notifyCallbackHandlerPseudoConnect();
        }
    }
    
    
    public void close() throws IOException {
        parentPool.getOutboundConnectionCache().release(underlyingConnectorHandler, 0);
    }
    
    public long read(ByteBuffer byteBuffer, boolean blocking) throws IOException {
        return underlyingConnectorHandler.read(byteBuffer, blocking);
    }
    
    public long write(ByteBuffer byteBuffer, boolean blocking) throws IOException {
        return underlyingConnectorHandler.write(byteBuffer, blocking);
    }
    
    public void finishConnect(SelectionKey key) {
        // Call underlying finishConnect only if connection was just established
        if (connectExecutor.wasCalled()) {
            try {
                underlyingConnectorHandler.finishConnect(key);
            } catch (IOException ex){
                Controller.logger().severe(ex.getMessage());
            }
        }
    }
    
    public void setController(Controller controller) {
        underlyingConnectorHandler.setController(controller);
    }
    
    public Controller getController() {
        return underlyingConnectorHandler.getController();
    }
    
    public ConnectorHandler getUnderlyingConnectorHandler() {
        return underlyingConnectorHandler;
    }

    public SelectableChannel getUnderlyingChannel() {
        return underlyingConnectorHandler.getUnderlyingChannel();
    }
    
    public CallbackHandler getCallbackHandler() {
        return underlyingConnectorHandler.getCallbackHandler();
    }
    
    public void setCallbackHandler(CallbackHandler callbackHandler) {
        underlyingConnectorHandler.setCallbackHandler(callbackHandler);
    }
    
    public SelectorHandler getSelectorHandler() {
        return underlyingConnectorHandler.getSelectorHandler();
    }    
    
    private void notifyCallbackHandlerPseudoConnect() throws ClosedChannelException {
        Selector protocolSelector = underlyingConnectorHandler.getSelectorHandler().getSelector();
        SelectionKey key = underlyingConnectorHandler.getUnderlyingChannel().keyFor(protocolSelector);
        if (key == null) {
            // Register channel on selector
            key = underlyingConnectorHandler.getUnderlyingChannel().
                    register(protocolSelector, SelectionKey.OP_CONNECT);
        }
        
        assert key != null;
        
        key.attach(CallbackHandlerSelectionKeyAttachment.create(this));
        final Context context = parentPool.getController().pollContext(key);
        onConnect(new IOEvent.DefaultIOEvent<Context>(context));
    }
    
    //---------------------- CallbackHandler implementation --------------------------------
    public void onConnect(IOEvent ioEvent) {
        if (underlyingCallbackHandler == null) {
            underlyingCallbackHandler = new CallbackHandler<Context>(){
                public void onConnect(IOEvent<Context> ioEvent) {
                    SelectionKey key = ioEvent.attachment().getSelectionKey();
                    finishConnect(key);
                    ioEvent.attachment().getSelectorHandler().register(key,SelectionKey.OP_READ);
                }
                public void onRead(IOEvent<Context> ioEvent) {}
                public void onWrite(IOEvent<Context> ioEvent) {}
            };
        }
        
        underlyingCallbackHandler.onConnect(ioEvent);
    }
    
    public void onRead(IOEvent ioEvent) {
        underlyingCallbackHandler.onRead(ioEvent);
    }
    
    public void onWrite(IOEvent ioEvent) {
        underlyingCallbackHandler.onWrite(ioEvent);
    }
    
    /**
     * Class is responsible for execution corresponding
     * underlying {@link ConnectorHandler} connect method
     */
    private class ConnectExecutor {
        private int methodNumber;
        private SocketAddress remoteAddress;
        private SocketAddress localAddress;
        private SelectorHandler selectorHandler;
        private CallbackHandler callbackHandler;
        private boolean wasCalled;
        
        public void setConnectorHandler(ConnectorHandler connectorHandler) {
            underlyingConnectorHandler = connectorHandler;
        }
        
        public void setParameters(SocketAddress remoteAddress, CallbackHandler callbackHandler, SelectorHandler selectorHandler) {
            setParameters(remoteAddress, null, callbackHandler, selectorHandler);
            methodNumber = 1;
        }
        
        public void setParameters(SocketAddress remoteAddress, CallbackHandler callbackHandler) {
            setParameters(remoteAddress, null, callbackHandler);
            methodNumber = 2;
        }
        
        public void setParameters(SocketAddress remoteAddress) {
            setParameters(remoteAddress, (SocketAddress) null);
            methodNumber = 3;
        }
        
        public void setParameters(SocketAddress remoteAddress, SocketAddress localAddress, CallbackHandler callbackHandler,SelectorHandler selectorHandler) {
            wasCalled = false;
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
            this.callbackHandler = callbackHandler;
            this.selectorHandler = selectorHandler;
            methodNumber = 4;
        }
        
        public void setParameters(SocketAddress remoteAddress, SocketAddress localAddress, CallbackHandler callbackHandler) {
            setParameters(remoteAddress, localAddress, callbackHandler , null);
            methodNumber = 5;
        }
        
        public void setParameters(SocketAddress remoteAddress, SocketAddress localAddress) {
            setParameters(remoteAddress, localAddress, null);
            methodNumber = 6;
        }
        
        public boolean wasCalled() {
            return wasCalled;
        }
        
        public void invokeProtocolConnect() throws IOException {
            wasCalled = true;
            switch(methodNumber) {
            case 1: underlyingConnectorHandler.connect(remoteAddress, CacheableConnectorHandler.this, selectorHandler);
            break;
            case 2:
            case 3: underlyingConnectorHandler.connect(remoteAddress, CacheableConnectorHandler.this);
            break;
            case 4: underlyingConnectorHandler.connect(remoteAddress, localAddress, CacheableConnectorHandler.this, selectorHandler);
            break;
            case 5:
            case 6: underlyingConnectorHandler.connect(remoteAddress, localAddress, CacheableConnectorHandler.this);
            break;
            default: throw new IllegalStateException("Can not find appropriate connect method: " + methodNumber);
            }
        }
    }

    //---------------------- ContactInfo implementation --------------------------------
    private static class CacheableConnectorInfo implements ContactInfo<ConnectorHandler> {
        private SelectorHandler selectorHandler;
        private CacheableConnectorHandlerPool parentPool;
        private ConnectExecutor connectExecutor;
        private Protocol protocol;
        private SocketAddress targetAddress;

        public CacheableConnectorInfo(CacheableConnectorHandlerPool parentPool,
                ConnectExecutor connectExecutor,
                Protocol protocol, SocketAddress targetAddress) {
            this.parentPool = parentPool;
            this.connectExecutor = connectExecutor;
            this.protocol = protocol;
            this.targetAddress = targetAddress;
        }
        

        public ConnectorHandler createConnection() throws IOException {
            ConnectorHandler connectorHandler =
                    parentPool.getProtocolConnectorHandlerPool().
                    acquireConnectorHandler(protocol);

            connectExecutor.setConnectorHandler(connectorHandler);
            connectExecutor.invokeProtocolConnect();

            selectorHandler = connectorHandler.getSelectorHandler();
            return connectorHandler;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getClass().getName());
            sb.append(" targetAddress: ");
            sb.append(targetAddress);
            sb.append(" protocol: ");
            sb.append(protocol);
            sb.append(" hashCode: ");
            sb.append(super.hashCode());
            return sb.toString();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final CacheableConnectorInfo other = (CacheableConnectorInfo) obj;
            if (this.selectorHandler != null && other.selectorHandler != null) {
                if (this.selectorHandler != other.selectorHandler &&
                        !this.selectorHandler.equals(other.selectorHandler)) {
                    return false;
                }
            }
            if (this.protocol != other.protocol) {
                return false;
            }
            if (this.targetAddress != other.targetAddress &&
                    (this.targetAddress == null ||
                    !this.targetAddress.equals(other.targetAddress))) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 47 * hash + 
                    (this.protocol != null ?
                        this.protocol.hashCode() : 0);
            hash = 47 * hash +
                    (this.targetAddress != null ?
                        this.targetAddress.hashCode() : 0);
            return hash;
        }

        //----- Override hashCode(), equals() as part of ContactInfo implementation -----------

    }
}
