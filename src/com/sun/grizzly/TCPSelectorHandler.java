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

package com.sun.grizzly;

import com.sun.grizzly.SelectionKeyOP.ConnectSelectionKeyOP;
import com.sun.grizzly.async.AsyncQueueReader;
import com.sun.grizzly.async.AsyncQueueReaderContextTask;
import com.sun.grizzly.async.AsyncQueueWriter;
import com.sun.grizzly.async.TCPAsyncQueueWriter;
import com.sun.grizzly.async.AsyncQueueWriterContextTask;
import com.sun.grizzly.async.TCPAsyncQueueReader;
import com.sun.grizzly.util.Cloner;
import com.sun.grizzly.util.Copyable;
import com.sun.grizzly.util.LinkedTransferQueue;
import com.sun.grizzly.util.SelectionKeyAttachment;
import com.sun.grizzly.util.State;
import com.sun.grizzly.util.StateHolder;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A SelectorHandler handles all java.nio.channels.Selector operations.
 * One or more instance of a Selector are handled by SelectorHandler.
 * The logic for processing of SelectionKey interest (OP_ACCEPT,OP_READ, etc.)
 * is usually defined using an instance of SelectorHandler.
 *
 * This class represents a TCP implementation of a SelectorHandler.
 * This class first bind a ServerSocketChannel to a TCP port and then start
 * waiting for NIO events.
 *
 * @author Jeanfrancois Arcand
 */
public class TCPSelectorHandler implements SelectorHandler {


    /**
     * The ConnectorInstanceHandler used to return a new or pooled
     * ConnectorHandler
     */
    protected ConnectorInstanceHandler connectorInstanceHandler;


    /**
     * The list of {@link SelectionKeyOP} to register next time the
     * Selector.select is invoked.
     * can be combined read+write interest or Connect
     */
    protected final LinkedTransferQueue<SelectionKeyOP> opToRegister
            = new LinkedTransferQueue<SelectionKeyOP>();

    /**
     *  SelectionKeys to be registered with Read interest in the next Selector.select();
     */
    private final LinkedTransferQueue<SelectionKey> readOpToRegister
            = new LinkedTransferQueue<SelectionKey>();

    /**
     *  SelectionKeys to be registered with Write interest in the next Selector.select();
     */
    private final LinkedTransferQueue<SelectionKey> writeOpToRegister
            = new LinkedTransferQueue<SelectionKey>();

    /**
     * SelectionKeys to be registered with Read and Write interest in the next Selector.select();
     */
    private final LinkedTransferQueue<SelectionKey> readWriteOpToRegister
            = new LinkedTransferQueue<SelectionKey>();
    /**
     * The socket tcpDelay.
     *
     * Default value for tcpNoDelay is disabled (set to true).
     */
    protected boolean tcpNoDelay = true;


    /**
     * The socket reuseAddress
     */
    protected boolean reuseAddress = true;


    /**
     * The socket linger.
     */
    protected int linger = -1;


    /**
     * The socket time out
     */
    protected int socketTimeout = -1;


    protected Logger logger;


    /**
     * The server socket time out
     */
    protected int serverTimeout = 0;


    /**
     * The inet address to use when binding.
     */
    protected InetAddress inet;


    /**
     * The default TCP port.
     */
    protected int port = 18888;


    /**
     * The ServerSocket instance.
     */
    protected ServerSocket serverSocket;


    /**
     * The ServerSocketChannel.
     */
    protected ServerSocketChannel serverSocketChannel;


    /**
     * The single Selector.
     */
    protected Selector selector;


    /**
     * The Selector time out.
     */
    protected long selectTimeout = 1000;


    /**
     * Server socket backlog.
     */
    protected int ssBackLog = 4096;


    /**
     * Is this used for client only or client/server operation.
     */
    protected Role role = Role.CLIENT_SERVER;


    /**
     * The SelectionKeyHandler associated with this SelectorHandler.
     */
    protected SelectionKeyHandler selectionKeyHandler;


    /**
     * The ProtocolChainInstanceHandler used by this instance. If not set, and instance
     * of the DefaultInstanceHandler will be created.
     */
    protected ProtocolChainInstanceHandler instanceHandler;


    /**
     * The {@link ExecutorService} used by this instance. If null -
     * {@link Controller}'s {@link ExecutorService} will be used
     */
    protected ExecutorService threadPool;


    /**
     * {@link AsyncQueueWriter}
     */
    protected AsyncQueueWriter asyncQueueWriter;


    /**
     * {@link AsyncQueueWriter}
     */
    protected AsyncQueueReader asyncQueueReader;


    /**
     * Attributes, associated with the {@link SelectorHandler} instance
     */
    protected Map<String, Object> attributes;

    /**
     * This {@link SelectorHandler} StateHolder, which is shared among
     * SelectorHandler and its clones
     */
    protected StateHolder<State> stateHolder = new StateHolder<State>(true);

    /**
     * Flag, which shows whether shutdown was called for this {@link SelectorHandler}
     */
    protected final AtomicBoolean isShutDown = new AtomicBoolean(false);

    public TCPSelectorHandler(){
        this(Role.CLIENT_SERVER);
    }


    /**
     * Create a TCPSelectorHandler only used with ConnectorHandler.
     *
     * @param isClient true if this SelectorHandler is only used
     * to handle ConnectorHandler.
     */
    public TCPSelectorHandler(boolean isClient) {
        this(boolean2Role(isClient));
    }


    /**
     * Create a TCPSelectorHandler only used with ConnectorHandler.
     *
     * @param role the <tt>TCPSelectorHandler</tt> {@link Role}
     */
    public TCPSelectorHandler(Role role) {
        this.role = role;
        logger = Controller.logger();
    }

    public void copyTo(Copyable copy) {
        TCPSelectorHandler copyHandler = (TCPSelectorHandler) copy;
        copyHandler.selector = selector;
        if (selectionKeyHandler != null) {
            copyHandler.setSelectionKeyHandler(Cloner.clone(selectionKeyHandler));
        }

        copyHandler.attributes = attributes;
        copyHandler.selectTimeout = selectTimeout;
        copyHandler.serverTimeout = serverTimeout;
        copyHandler.inet = inet;
        copyHandler.port = port;
        copyHandler.ssBackLog = ssBackLog;
        copyHandler.tcpNoDelay = tcpNoDelay;
        copyHandler.linger = linger;
        copyHandler.socketTimeout = socketTimeout;
        copyHandler.logger = logger;
        copyHandler.reuseAddress = reuseAddress;
        copyHandler.connectorInstanceHandler = connectorInstanceHandler;
        copyHandler.stateHolder = stateHolder;
    }


    /**
     * Return the set of SelectionKey registered on this Selector.
     */
    public Set<SelectionKey> keys(){
        if (selector != null){
            return selector.keys();
        } else {
            throw new IllegalStateException("Selector is not created!");
        }
    }


    /**
     * Is the Selector open.
     */
    public boolean isOpen(){
        if (selector != null){
            return selector.isOpen();
        } else {
            return false;
        }
    }


    /**
     * Before invoking {@link Selector#select}, make sure the {@link ServerSocketChannel}
     * has been created. If true, then register all {@link SelectionKey} to the {@link Selector}.
     * @param ctx {@link Context}
     */
    public void preSelect(Context ctx) throws IOException {
        if (asyncQueueReader == null) {
            asyncQueueReader = new TCPAsyncQueueReader(this);
        }

        if (asyncQueueWriter == null) {
            asyncQueueWriter = new TCPAsyncQueueWriter(this);
        }

        if (selector == null){
            initSelector(ctx);
        } else {
            processPendingOperations(ctx);
        }
    }

    /**
     * init the Selector
     * @param ctx
     * @throws java.io.IOException
     */
    private final void initSelector(Context ctx) throws IOException{
        try {
            isShutDown.set(false);

            connectorInstanceHandler = new ConnectorInstanceHandler.
                    ConcurrentQueueDelegateCIH(
                    getConnectorInstanceHandlerDelegate());

            // Create the socket listener
            selector = Selector.open();

            if (role != Role.CLIENT){
                serverSocketChannel = ServerSocketChannel.open();
                serverSocket = serverSocketChannel.socket();
                serverSocket.setReuseAddress(reuseAddress);
                if ( inet == null){
                    serverSocket.bind(new InetSocketAddress(port),ssBackLog);
                } else {
                    serverSocket.bind(new InetSocketAddress(inet,port),ssBackLog);
                }

                serverSocketChannel.configureBlocking(false);
                serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            }
            ctx.getController().notifyReady();
        } catch (SocketException ex){
            throw new BindException(ex.getMessage() + ": " + port + "=" + this);
        }

        if (role != Role.CLIENT){
            serverSocket.setSoTimeout(serverTimeout);
        }
    }

    /**
     *
     * @param ctx
     * @throws java.io.IOException
     */
    protected void processPendingOperations(Context ctx) throws IOException {
        //investigate if its worthwile to swap LTQ references with a local one to completely avoid thread contention between the producers and the consumer
        //the cost for doing so is cheap, just a reference swap. volatile for contended LTQ and normal reference for the local one.

        SelectionKeyOP operation;
        while((operation = opToRegister.poll()) != null) {
            if ((operation.getOp() & SelectionKey.OP_CONNECT) != 0) {
                onConnectOp(ctx, (SelectionKeyOP.ConnectSelectionKeyOP) operation);
            } else{
                if (operation.getChannel().isOpen()){
                    selectionKeyHandler.register(operation.getChannel(),operation.getOp());
                }
            }
        }

        SelectionKey key;
        while((key=readWriteOpToRegister.poll()) != null){
            if (key.isValid()){
                selectionKeyHandler.
                        register(key, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
            }
        }
        
        while((key=writeOpToRegister.poll()) != null){
            if (key.isValid()){
                selectionKeyHandler.register(key, SelectionKey.OP_WRITE);
            }
        }

        while((key=readOpToRegister.poll()) != null){
            if (key.isValid()){
                selectionKeyHandler.register(key, SelectionKey.OP_READ);
            }
        }
    }


    /**
     * Handle new OP_CONNECT ops.
     */
    protected void onConnectOp(Context ctx,
            SelectionKeyOP.ConnectSelectionKeyOP selectionKeyOp) throws IOException {
        SocketChannel socketChannel = (SocketChannel) selectionKeyOp.getChannel();
        SocketAddress remoteAddress = selectionKeyOp.getRemoteAddress();
        CallbackHandler callbackHandler = selectionKeyOp.getCallbackHandler();

        CallbackHandlerSelectionKeyAttachment attachment =
                CallbackHandlerSelectionKeyAttachment.create(callbackHandler);

        SelectionKey key = socketChannel.register(selector, 0, attachment);
        attachment.associateKey(key);

        boolean isConnected;
        try {
            isConnected = socketChannel.connect(remoteAddress);
        } catch(Exception e) {
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "Exception occured when tried to connect socket", e);
            }

            // set isConnected to true to let callback handler to know about the problem happened
            isConnected = true;
        }

        // if channel was connected immediately or exception occured
        if (isConnected) {
            onConnectInterest(key, ctx);
        } else {
            key.interestOps(SelectionKey.OP_CONNECT);
        }
    }


    /**
     * Execute the Selector.select(...) operations.
     * @param ctx {@link Context}
     * @return {@link Set} of {@link SelectionKey}
     */
    public Set<SelectionKey> select(Context ctx) throws IOException{
        selector.select(selectTimeout);
        return selector.selectedKeys();
    }


    /**
     * Invoked after Selector.select().
     * @param ctx {@link Context}
     */
    public void postSelect(Context ctx) {
        //Selector.keys() performs isOpen() so we dont need to do it a second time.
        selectionKeyHandler.expire(keys().iterator());
    }


    /**
     * Register a SelectionKey to this Selector.
     */
    public void register(SelectionKey key, int ops) {
        if (key == null) {
            throw new NullPointerException("SelectionKey parameter is null");
        }

        switch(ops){
            case SelectionKey.OP_READ: readOpToRegister.offer(key);  break;
            case SelectionKey.OP_WRITE: writeOpToRegister.offer(key); break;
            case SelectionKey.OP_WRITE | SelectionKey.OP_READ:
                readWriteOpToRegister.offer(key);  break;
            default:
                opToRegister.offer(new SelectionKeyOP(key,ops));
        }
        selector.wakeup();
    }

    public void register(SelectableChannel channel, int ops) {
        if (channel == null) {
            throw new NullPointerException("SelectableChannel parameter is null");
        }

        opToRegister.offer(new SelectionKeyOP(null,ops,channel));
        selector.wakeup();
    }

    /**
     * Register a CallBackHandler to this Selector.
     *
     * @param remoteAddress remote address to connect
     * @param localAddress local address to bin
     * @param callbackHandler {@link CallbackHandler}
     * @throws java.io.IOException
     */
    protected void connect(SocketAddress remoteAddress, SocketAddress localAddress,
            CallbackHandler callbackHandler) throws IOException {

        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.socket().setReuseAddress(reuseAddress);
        if (localAddress != null) {
            socketChannel.socket().bind(localAddress);
        }

        socketChannel.configureBlocking(false);

        SelectionKeyOP.ConnectSelectionKeyOP keyOP = new ConnectSelectionKeyOP();

        keyOP.setOp(SelectionKey.OP_CONNECT);
        keyOP.setChannel(socketChannel);
        keyOP.setRemoteAddress(remoteAddress);
        keyOP.setCallbackHandler(callbackHandler);
        opToRegister.offer(keyOP);
        selector.wakeup();
    }

    /**
     * {@inheritDoc}
     */
    public void pause() {
        stateHolder.setState(State.PAUSED);
    }

    /**
     * {@inheritDoc}
     */
    public void resume() {
        if (!State.PAUSED.equals(stateHolder.getState(false))) {
            throw new IllegalStateException("SelectorHandler is not in PAUSED state, but: " +
                    stateHolder.getState(false));
        }

        stateHolder.setState(State.STARTED);
    }

    /**
     * {@inheritDoc}
     */
    public StateHolder<State> getStateHolder() {
        return stateHolder;
    }

    /**
     * Shuntdown this instance by closing its Selector and associated channels.
     */
    public void shutdown() {
        // If shutdown was called for this SelectorHandler
        if (isShutDown.getAndSet(true)) return;

        stateHolder.setState(State.STOPPED);

        if (selector != null) {
            try {
                for (SelectionKey selectionKey : selector.keys()) {
                    selectionKeyHandler.close(selectionKey);
                }
            } catch (ClosedSelectorException e) {
                // If Selector is already closed - OK
            } catch (ConcurrentModificationException e) {
                // Someone else works with keys. Create copy
                Object[] keys = selector.keys().toArray();
                for (Object selectionKey : keys) {
                    selectionKeyHandler.close((SelectionKey) selectionKey);
                }

            }
        }

        try{
            if (serverSocket != null)
                serverSocket.close();
        } catch (Throwable ex){
            Controller.logger().log(Level.SEVERE,
                    "serverSocket.close",ex);
        }

        try{
            if (serverSocketChannel != null)
                serverSocketChannel.close();
        } catch (Throwable ex){
            Controller.logger().log(Level.SEVERE,
                    "serverSocketChannel.close",ex);
        }

        try{
            if (selector != null)
                selector.close();
        } catch (Throwable ex){
            Controller.logger().log(Level.SEVERE,
                    "selector.close",ex);
        }

        if (asyncQueueReader != null) {
            asyncQueueReader.close();
            asyncQueueReader = null;
        }

        if (asyncQueueWriter != null) {
            asyncQueueWriter.close();
            asyncQueueWriter = null;
        }

        readOpToRegister.clear();
        writeOpToRegister.clear();
        opToRegister.clear();

        attributes = null;
    }

    /**
     * {@inheritDoc}
     */
    public SelectableChannel acceptWithoutRegistration(SelectionKey key)
    throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel channel = server.accept();
        return channel;
    }

    /**
     * Handle OP_ACCEPT.
     * @param ctx {@link Context}
     * @return always returns false
     */
    public boolean onAcceptInterest(SelectionKey key,
            Context ctx) throws IOException{
        SelectableChannel channel = acceptWithoutRegistration(key);

        if (channel != null) {
            configureChannel(channel);
            SelectionKey readKey =
                    channel.register(selector, SelectionKey.OP_READ);
            readKey.attach(System.currentTimeMillis());
        }
        return false;
    }

    /**
     * Handle OP_READ.
     * @param ctx {@link Context}
     * @param key {@link SelectionKey}
     * @return false if handled by a {@link CallbackHandler}, otherwise true
     */
    public boolean onReadInterest(final SelectionKey key,final Context ctx) throws IOException{
        // disable OP_READ on key before doing anything else
        key.interestOps(key.interestOps() & (~SelectionKey.OP_READ));

        if (asyncQueueReader.isReady(key)) {
            invokeAsyncQueueReader(pollContext(ctx, key, Context.OpType.OP_READ));
            return false;
        }
        Object attach = SelectionKeyAttachment.getAttachment(key);
        if (attach instanceof CallbackHandler){
            final Context context = pollContext(ctx, key, Context.OpType.OP_READ);
            invokeCallbackHandler((CallbackHandler) attach, context);
            return false;
        }
        return true;
    }


    /**
     * Handle OP_WRITE.
     *
     * @param key {@link SelectionKey}
     * @param ctx {@link Context}
     */
    public boolean onWriteInterest(final SelectionKey key,final Context ctx) throws IOException{
        // disable OP_WRITE on key before doing anything else
        key.interestOps(key.interestOps() & (~SelectionKey.OP_WRITE));

        if (asyncQueueWriter.isReady(key)) {
            invokeAsyncQueueWriter(pollContext(ctx, key, Context.OpType.OP_WRITE));
            return false;
        }
        Object attach = SelectionKeyAttachment.getAttachment(key);
        if (attach instanceof CallbackHandler){
            final Context context = pollContext(ctx, key, Context.OpType.OP_WRITE);
            invokeCallbackHandler((CallbackHandler) attach, context);
            return false;
        }
        return true;
    }


    /**
     * Handle OP_CONNECT.
     * @param key {@link SelectionKey}
     * @param ctx {@link Context}
     */
    public boolean onConnectInterest(final SelectionKey key, Context ctx)
    throws IOException{
        try {
            //logical replacement of 3 interest changes with 1 that is the complement to them.
            //if opaccept is known to not be of interest we could 0 the interest instead.
             key.interestOps(key.interestOps() & (SelectionKey.OP_ACCEPT) );
             /*
            // disable OP_CONNECT on key before doing anything else
            key.interestOps(key.interestOps() & (~SelectionKey.OP_CONNECT));

            // No OP_READ nor OP_WRITE allowed yet.
            key.interestOps(key.interestOps() & (~SelectionKey.OP_WRITE));
            key.interestOps(key.interestOps() & (~SelectionKey.OP_READ));*/

        } catch(CancelledKeyException e) {
            // Even if key was cancelled - we need to notify CallBackHandler
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "CancelledKeyException occured when tried to change key interests", e);
            }
        }

        Object attach = SelectionKeyAttachment.getAttachment(key);
        if (attach instanceof CallbackHandler){
            final Context context = pollContext(ctx, key, Context.OpType.OP_CONNECT);
            invokeCallbackHandler((CallbackHandler) attach, context);
        }
        return false;
    }


    /**
     * Invoke a CallbackHandler via a Context instance.
     * @param context {@link Context}
     * @throws java.io.IOException
     */
    protected void invokeCallbackHandler(CallbackHandler callbackHandler,
            Context context) throws IOException {

        if (!(context instanceof NIOContext)){
            logger.severe("Invalid Context instance: "
                    + context.getClass().getName());
            return;
        }

        IOEvent<Context>ioEvent = new IOEvent.DefaultIOEvent<Context>(context);
        context.setIOEvent(ioEvent);

        // Added because of incompatibility with Grizzly 1.6.0
        ((NIOContext)context).setSelectorHandler(this);

        CallbackHandlerContextTask task = CallbackHandlerContextTask.poll();
        task.setCallBackHandler(callbackHandler);
        boolean isRunInSeparateThread = true;

        if (callbackHandler instanceof CallbackHandlerDescriptor) {
            isRunInSeparateThread =
                    ((CallbackHandlerDescriptor) callbackHandler).
                    isRunInSeparateThread(context.getCurrentOpType());
        }
        context.execute(task, isRunInSeparateThread);
    }


    /**
     * Invoke a {@link AsyncQueueReader}
     * @param context {@link Context}
     * @throws java.io.IOException
     */
    protected void invokeAsyncQueueReader(Context context) throws IOException {
        AsyncQueueReaderContextTask task = AsyncQueueReaderContextTask.poll();
        task.setAsyncQueueReader(asyncQueueReader);
        context.execute(task);
    }


    /**
     * Invoke a {@link AsyncQueueWriter}
     * @param context {@link Context}
     * @throws java.io.IOException
     */
    protected void invokeAsyncQueueWriter(Context context) throws IOException {
        AsyncQueueWriterContextTask task = AsyncQueueWriterContextTask.poll();
        task.setAsyncQueueWriter(asyncQueueWriter);
        context.execute(task);
    }


    /**
     * Return an instance of the default {@link ConnectorHandler},
     * which is the {@link TCPConnectorHandler}
     * @return {@link ConnectorHandler}
     */
    public ConnectorHandler acquireConnectorHandler(){
        if (selector == null || !selector.isOpen()){
            throw new IllegalStateException("SelectorHandler not yet started");
        }

        ConnectorHandler connectorHandler = connectorInstanceHandler.acquire();
        connectorHandler.setController(Controller.getHandlerController(this));
        return connectorHandler;
    }


    /**
     * Release a ConnectorHandler.
     */
    public void releaseConnectorHandler(ConnectorHandler connectorHandler){
        connectorInstanceHandler.release(connectorHandler);
    }


    /**
     * A token decribing the protocol supported by an implementation of this
     * interface
     */
    public Controller.Protocol protocol(){
        return Controller.Protocol.TCP;
    }
    // ------------------------------------------------------ Utils ----------//


    /**
     * {@inheritDoc}
     */
    public void configureChannel(SelectableChannel channel) throws IOException{
        Socket socket = ((SocketChannel) channel).socket();

        channel.configureBlocking(false);

        if (!channel.isOpen()){
            return;
        }

        try{
            if(socketTimeout >= 0 ) {
                socket.setSoTimeout(socketTimeout);
            }
        } catch (SocketException ex){
            if (logger.isLoggable(Level.FINE)){
                logger.log(Level.FINE,
                        "setSoTimeout exception ",ex);
            }
        }

        try{
            if(linger >= 0 ) {
                socket.setSoLinger( true, linger);
            }
        } catch (SocketException ex){
            if (logger.isLoggable(Level.FINE)){
                logger.log(Level.FINE,
                        "setSoLinger exception ",ex);
            }
        }

        try{
            socket.setTcpNoDelay(tcpNoDelay);
        } catch (SocketException ex){
            if (logger.isLoggable(Level.FINE)){
                logger.log(Level.FINE,
                        "setTcpNoDelay exception ",ex);
            }
        }

        try{
            socket.setReuseAddress(reuseAddress);
        } catch (SocketException ex){
            if (logger.isLoggable(Level.FINE)){
                logger.log(Level.FINE,
                        "setReuseAddress exception ",ex);
            }
        }
    }


    // ------------------------------------------------------ Properties -----//

    public final Selector getSelector() {
        return selector;
    }

    public final void setSelector(Selector selector) {
        this.selector = selector;
    }

    /**
     * {@inheritDoc}
     */
    public AsyncQueueReader getAsyncQueueReader() {
        return asyncQueueReader;
    }

    /**
     * {@inheritDoc}
     */
    public AsyncQueueWriter getAsyncQueueWriter() {
        return asyncQueueWriter;
    }

    public long getSelectTimeout() {
        return selectTimeout;
    }

    public void setSelectTimeout(long selectTimeout) {
        this.selectTimeout = selectTimeout;
    }

    public int getServerTimeout() {
        return serverTimeout;
    }

    public void setServerTimeout(int serverTimeout) {
        this.serverTimeout = serverTimeout;
    }

    public InetAddress getInet() {
        return inet;
    }

    public void setInet(InetAddress inet) {
        this.inet = inet;
    }

    /**
     * Gets this {@link SelectorHandler} current role.
     * <tt>TCPSelectorHandler</tt> could act as client, which corresponds to
     * {@link Role#CLIENT} or client-server, which corresponds
     * to the {@link Role#CLIENT_SERVER}
     *
     * @return the {@link Role}
     */
    public Role getRole() {
        return role;
    }

    /**
     * Sets this {@link SelectorHandler} current role.
     * <tt>TCPSelectorHandler</tt> could act as client, which corresponds to
     * {@link Role#CLIENT} or client-server, which corresponds
     * to the {@link Role#CLIENT_SERVER}
     *
     * @param role the {@link Role}
     */
    public void setRole(Role role) {
        this.role = role;
    }

    /**
     * Returns port number {@link SelectorHandler} is listening on
     * Similar to <code>getPort()</code>, but getting port number directly from
     * connection ({@link ServerSocket}, {@link DatagramSocket}).
     * So if default port number 0 was set during initialization, then <code>getPort()</code>
     * will return 0, but getPortLowLevel() will
     * return port number assigned by OS.
     *
     * @return port number or -1 if {@link SelectorHandler} was not initialized for accepting connections.
     */
    public int getPortLowLevel() {
        if (serverSocket != null) {
            return serverSocket.getLocalPort();
        }

        return -1;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getSsBackLog() {
        return ssBackLog;
    }

    public void setSsBackLog(int ssBackLog) {
        this.ssBackLog = ssBackLog;
    }


    /**
     * Return the tcpNoDelay value used by the underlying accepted Sockets.
     *
     * Also see setTcpNoDelay(boolean tcpNoDelay)
     */
    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }


    /**
     * Enable (true) or disable (false) the underlying Socket's
     * tcpNoDelay.
     *
     * Default value for tcpNoDelay is enabled (set to true), as according to
     * the performance tests, it performs better for most cases.
     *
     * The Connector side should also set tcpNoDelay the same as it is set here
     * whenever possible.
     */
    public void setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
    }

    public int getLinger() {
        return linger;
    }

    public void setLinger(int linger) {
        this.linger = linger;
    }

    public int getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(int socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    public Logger getLogger() {
        return logger;
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    public boolean isReuseAddress() {
        return reuseAddress;
    }

    public void setReuseAddress(boolean reuseAddress) {
        this.reuseAddress = reuseAddress;
    }

    /**
     * {@inheritDoc}
     */
    public ExecutorService getThreadPool(){
        return threadPool;
    }


    /**
     * {@inheritDoc}
     */
    public void setThreadPool(ExecutorService threadPool){
        this.threadPool = threadPool;
    }


    /**
     * {@inheritDoc}
     */
    public Class<? extends SelectionKeyHandler> getPreferredSelectionKeyHandler() {
        return DefaultSelectionKeyHandler.class;
    }


    /**
     * Get the SelectionKeyHandler associated with this SelectorHandler.
     */
    public SelectionKeyHandler getSelectionKeyHandler() {
        return selectionKeyHandler;
    }


    /**
     * Set SelectionKeyHandler associated with this SelectorHandler.
     */
    public void setSelectionKeyHandler(SelectionKeyHandler selectionKeyHandler) {
        this.selectionKeyHandler = selectionKeyHandler;
        this.selectionKeyHandler.setSelectorHandler(this);
    }


    /**
     * Set the {@link ProtocolChainInstanceHandler} to use for
     * creating instance of {@link ProtocolChain}.
     */
    public void setProtocolChainInstanceHandler(ProtocolChainInstanceHandler
            instanceHandler){
        this.instanceHandler = instanceHandler;
    }


    /**
     * Return the {@link ProtocolChainInstanceHandler}
     */
    public ProtocolChainInstanceHandler getProtocolChainInstanceHandler(){
        return instanceHandler;
    }

    /**
     * {@inheritDoc}
     */
    public void closeChannel(SelectableChannel channel) {
        // channel could be either SocketChannel or ServerSocketChannel
        if (channel instanceof SocketChannel) {
            Socket socket = ((SocketChannel) channel).socket();

            try {
                if (!socket.isInputShutdown()) socket.shutdownInput();
            } catch (IOException e) {
                logger.log(Level.FINEST, "Unexpected exception during channel inputShutdown", e);
            }

            try {
                if (!socket.isOutputShutdown()) socket.shutdownOutput();
            } catch (IOException e) {
                logger.log(Level.FINEST, "Unexpected exception during channel outputShutdown", e);
            }

            try{
                socket.close();
            } catch (IOException e) {
                logger.log(Level.FINEST, "Unexpected exception during socket close", e);
            }
        }

        try{
            channel.close();
        } catch (IOException e){
            logger.log(Level.FINEST, "Unexpected exception during channel close", e);
        }

        if (asyncQueueReader != null) {
            asyncQueueReader.onClose(channel);
        }

        if (asyncQueueWriter != null) {
            asyncQueueWriter.onClose(channel);
        }
    }

    /**
     * Polls {@link Context} from pool and initializes it.
     *
     * @param serverContext {@link Controller} context
     * @param key {@link SelectionKey}
     * @return {@link Context}
     */
    protected Context pollContext(final Context serverContext,
            final SelectionKey key, final Context.OpType opType) {
        ProtocolChain protocolChain = instanceHandler != null ?
            instanceHandler.poll() :
            serverContext.getController().getProtocolChainInstanceHandler().poll();


        final Context ctx = serverContext.getController().pollContext(key, opType);

        if (!(ctx instanceof NIOContext)){
            logger.severe("Invalid Context instance: "
                    + ctx.getClass().getName());
            throw new RuntimeException("Invalid Context instance: "
                    + ctx.getClass().getName());
        }

        NIOContext context = (NIOContext)ctx;
        context.setSelectionKey(key);
        context.setSelectorHandler(this);
        context.setAsyncQueueReader(asyncQueueReader);
        context.setAsyncQueueWriter(asyncQueueWriter);
        context.setProtocolChain(protocolChain);
        return context;
    }

    //--------------- ConnectorInstanceHandler -----------------------------
    /**
     * Return <Callable>factory<Callable> object, which knows how
     * to create {@link ConnectorInstanceHandler} corresponding to the protocol
     * @return <Callable>factory</code>
     */
    protected Callable<ConnectorHandler> getConnectorInstanceHandlerDelegate() {
        return new Callable<ConnectorHandler>() {
            public ConnectorHandler call() throws Exception {
                return new TCPConnectorHandler();
            }
        };
    }

    // ----------- AttributeHolder interface implementation ----------- //

    /**
     * Remove a key/value object.
     * Method is not thread safe
     *
     * @param key - name of an attribute
     * @return  attribute which has been removed
     */
    public Object removeAttribute(String key) {
        if (attributes == null) return null;

        return attributes.remove(key);
    }

    /**
     * Set a key/value object.
     * Method is not thread safe
     *
     * @param key - name of an attribute
     * @param value - value of named attribute
     */
    public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<String, Object>();
        }

        attributes.put(key, value);
    }

    /**
     * Return an object based on a key.
     * Method is not thread safe
     *
     * @param key - name of an attribute
     * @return - attribute value for the <tt>key</tt>, null if <tt>key</tt>
     *           does not exist in <tt>attributes</tt>
     */
    public Object getAttribute(String key) {
        if (attributes == null) return null;

        return attributes.get(key);
    }


    /**
     * Set a {@link Map} of attribute name/value pairs.
     * Old {@link AttributeHolder} values will not be available.
     * Later changes of this {@link Map} will lead to changes to the current
     * {@link AttributeHolder}.
     *
     * @param attributes - map of name/value pairs
     */
    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }


    /**
     * Return a {@link Map} of attribute name/value pairs.
     * Updates, performed on the returned {@link Map} will be reflected in
     * this {@link AttributeHolder}
     *
     * @return - {@link Map} of attribute name/value pairs
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * Returns the {@link Role}, depending on isClient value
     * @param isClient <tt>true>tt>, if this <tt>SelectorHandler</tt> works in
     *          the client mode, or <tt>false</tt> otherwise.
     * @return {@link Role}
     */
    protected static Role boolean2Role(boolean isClient) {
        if (isClient) return Role.CLIENT;

        return Role.CLIENT_SERVER;
    }
}
