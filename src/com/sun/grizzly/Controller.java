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

import com.sun.grizzly.util.AttributeHolder;
import com.sun.grizzly.util.Cloner;
import com.sun.grizzly.util.ConcurrentLinkedQueuePool;
import com.sun.grizzly.util.Copyable;
import com.sun.grizzly.util.DefaultThreadPool;
import com.sun.grizzly.util.LinkedTransferQueue;
import com.sun.grizzly.util.State;
import com.sun.grizzly.util.StateHolder;
import com.sun.grizzly.util.SupportStateHolder;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.sun.grizzly.Context.OpType;

/**
 * <p>
 * Main entry point when using the Grizzly Framework. A Controller is composed
 * of Handlers, ProtocolChain and ExecutorService. All of those components are
 * configurable by client using the Grizzly Framework.
 * </p>
 *
 * <p>
 * A ProtocolChain implement the "Chain of Responsibility" pattern (for more info,
 * take a look at the classic "Gang of Four" design patterns book). Towards
 * that end, the Chain API models a computation as a series of "protocol filter"
 * that can be combined into a "protocol chain".
 * </p>
 * <p>
 * An Handler is a interface that can be implemented
 * by implemented by client of the Grizzly Framework to used to help handling
 * NIO operations. The Grizzly Framework define three Handlers:
 * </p>
 * <p><pre><code>
 * (1) SelectorHandler: A SelectorHandler handles all java.nio.channels.Selector
 *                     operations. One or more instance of a Selector are
 *                     handled by SelectorHandler. The logic for processing of
 *                     SelectionKey interest (OP_ACCEPT,OP_READ, etc.) is usually
 *                     defined using an instance of SelectorHandler.
 * (2) SelectionKeyHandler: A SelectionKeyHandler is used to handle the life
 *                          life cycle of a SelectionKey. Operations like canceling,
 *                          registering or closing are handled by SelectionKeyHandler.
 * (3) ProtocolChainInstanceHandler: An ProtocolChainInstanceHandler is where one or several ProtocolChain
 *                      are created and cached. An ProtocolChainInstanceHandler decide if
 *                      a stateless or statefull ProtocolChain needs to be created.
 * </code></pre></p>
 * <p>
 * By default, the Grizzly Framework bundles implementation for TCP
 * and UPD transport. The TCPSelectorHandler is instanciated by default. As an
 * example, supporting the HTTP protocol should only consist of adding the
 * appropriate ProtocolFilter like:
 * </p>
 * <p><pre><code>
 *       Controller sel = new Controller();
 *       sel.setProtocolChainInstanceHandler(new DefaultProtocolChainInstanceHandler(){
 *           public ProtocolChain poll() {
 *               ProtocolChain protocolChain = protocolChains.poll();
 *               if (protocolChain == null){
 *                   protocolChain = new DefaultProtocolChain();
 *                   protocolChain.addFilter(new ReadFilter());
 *                   protocolChain.addFilter(new HTTPParserFilter());
 *               }
 *               return protocolChain;
 *           }
 *       });
 *
 * </code></pre></p>
 * <p>
 * In the example above, a pool of ProtocolChain will be created, and all instance
 * of ProtocolChain will have their instance of ProtocolFilter. Hence the above
 * implementation can be called statefull. A stateless implementation would
 * instead consist of sharing the ProtocolFilter among ProtocolChain:
 * </p>
 * <p><pre><code>
 *       final Controller sel = new Controller();
 *       final ReadFilter readFilter = new ReadFilter();
 *       final LogFilter logFilter = new LogFilter();
 *
 *       sel.setProtocolChainInstanceHandler(new DefaultProtocolChainInstanceHandler(){
 *           public ProtocolChain poll() {
 *               ProtocolChain protocolChain = protocolChains.poll();
 *               if (protocolChain == null){
 *                   protocolChain = new DefaultProtocolChain();
 *                   protocolChain.addFilter(readFilter);
 *                   protocolChain.addFilter(logFilter);
 *               }
 *               return protocolChain;
 *           }
 *       });
 * </code></pre></p>
 * @author Jeanfrancois Arcand
 */
public class Controller implements Runnable, Lifecycle, Copyable,
        ConnectorHandlerPool, AttributeHolder, SupportStateHolder<State> {

    public enum Protocol { UDP, TCP , TLS, CUSTOM }


    /**
     * A cached list of Context. Context are by default stateless.
     */
    private ConcurrentLinkedQueuePool<Context> contexts;


    /**
     * The ProtocolChainInstanceHandler used by this instance. If not set, and instance
     * of the DefaultInstanceHandler will be created.
     */
    protected ProtocolChainInstanceHandler instanceHandler;


    /**
     * The SelectionKey Handler used by this instance. If not set, and instance
     * of the DefaultSelectionKeyHandler will be created.
     */
    protected SelectionKeyHandler selectionKeyHandler;


    /**
     * The SelectorHandler, which will manage connection accept,
     * if readThreadsCount > 0 and spread connection processing between
     * different read threads
     */
    protected ComplexSelectorHandler multiReadThreadSelectorHandler = null;


    /**
     * The ConnectorHandlerPool, which is responsible for creating/caching
     * ConnectorHandler instances.
     */
    protected ConnectorHandlerPool connectorHandlerPool = null;


    /**
     * The set of {@link SelectorHandler}s used by this instance. If not set, the instance
     * of the TCPSelectorHandler will be added by default.
     */
    protected LinkedTransferQueue<SelectorHandler> selectorHandlers;


    /**
     * Current {@link Controller} state
     */
    protected StateHolder<State> stateHolder;


    /**
     * The number of read threads
     */
    protected int readThreadsCount = 0;


    /**
     * The array of {@link Controller}s to be used for reading
     */
    protected ReadController[] readThreadControllers;


    /**
     * Default Logger.
     */
    protected static Logger logger = Logger.getLogger("grizzly");


    /**
     * Default Thread Pool (called ExecutorService).If not set, and instance
     * of the DefaultThreadPool will be created.
     */
    protected ExecutorService threadPool;


    /**
     * Collection of {@link Controller} state listeners, which
     * will are notified on {@link Controller} state change.
     */
    protected final Collection<ControllerStateListener> stateListeners =
            new LinkedTransferQueue<ControllerStateListener>();


    /**
     * Internal countdown counter of {@link SelectorHandler}s, which
     * are ready to process
     */
    protected AtomicInteger readySelectorHandlerCounter;

    /**
     * Internal countdown counter of {@link SelectorHandler}s, which stopped
     */
    protected AtomicInteger stoppedSelectorHandlerCounter;


    /**
     * <tt>true</tt> if OP_READ and OP_WRITE can be handled concurrently.
     */
    private boolean handleReadWriteConcurrently = true;


    /**
     * Attributes, associated with the {@link Controller} instance
     */
    protected Map<String, Object> attributes;


    /**
     * The current Controller instance.
     *
     */
    private final static LinkedTransferQueue<Controller> controllers =
            new LinkedTransferQueue<Controller>();


    // -------------------------------------------------------------------- //

    /**
     * Controller constructor
     */
    public Controller() {
        contexts = new ConcurrentLinkedQueuePool<Context>() {
            @Override
            public NIOContext newInstance() {
                return new NIOContext();
            }
        };

        stateHolder = new StateHolder<State>(true);
        initializeDefaults();
    }


    /**
     * This method initializes this Controller's default thread pool,
     * default ProtocolChainInstanceHandler, default SelectorHandler(s)
     * and default ConnectorHandlerPool.  These defaults can be overridden
     * after this Controller constructor is called and before calling
     * Controller.start() using this Controller's mutator methods to
     * set a different thread pool, ProtocolChainInstanceHandler,
     * SelectorHandler(s) or ConnectorHandlerPool.
     */
    private void initializeDefaults() {
        if (threadPool == null) {
            threadPool = new DefaultThreadPool();
        }
        if (instanceHandler == null) {
            instanceHandler = new DefaultProtocolChainInstanceHandler();
        }
        if (selectorHandlers == null){
            selectorHandlers = new LinkedTransferQueue<SelectorHandler>();
        }
        if (connectorHandlerPool == null) {
            connectorHandlerPool = new DefaultConnectorHandlerPool(this);
        }
        controllers.add(this);
    }


    /**
     * This method handle the processing of all Selector's interest op
     * (OP_ACCEPT,OP_READ,OP_WRITE,OP_CONNECT) by delegating to its Handler.
     * By default, all java.nio.channels.Selector operations are implemented
     * using SelectorHandler. All SelectionKey operations are implemented by
     * SelectionKeyHandler. Finally, ProtocolChain creation/re-use are implemented
     * by InstanceHandler.
     * @param selectorHandler - the {@link SelectorHandler}
     */
    protected void doSelect(SelectorHandler selectorHandler){
        final NIOContext serverCtx = pollContext(null,null);
        serverCtx.setSelectorHandler(selectorHandler);
        try {
            // Set the SelectionKeyHandler only if the SelectorHandler doesn't define one.
            if (selectorHandler.getSelectionKeyHandler() == null){
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "Set DefaultSelectionKeyHandler to SelectorHandler: " + selectorHandler);
                }
                SelectionKeyHandler assgnSelectionKeyHandler = null;
                if (selectorHandler.getPreferredSelectionKeyHandler() != null) {
                    Class<? extends SelectionKeyHandler> keyHandlerClass =
                                selectorHandler.getPreferredSelectionKeyHandler();
                    try {
                        assgnSelectionKeyHandler = keyHandlerClass.newInstance();
                        assgnSelectionKeyHandler.setSelectorHandler(selectorHandler);
                    } catch (Exception e) {
                        if (logger.isLoggable(Level.WARNING)) {
                            logger.log(Level.WARNING,
                                    "Exception initializing preffered SelectionKeyHandler '" +
                                    keyHandlerClass + "' for the SelectorHandler '" +
                                    selectorHandler + "'");
                        }
                    }
                }
                if (assgnSelectionKeyHandler == null) {
                    assgnSelectionKeyHandler = new DefaultSelectionKeyHandler(selectorHandler);
                }
                selectorHandler.setSelectionKeyHandler(assgnSelectionKeyHandler);
            }            
            selectorHandler.preSelect(serverCtx);

            Set<SelectionKey> readyKeys = selectorHandler.select(serverCtx);
            if (readyKeys.size() != 0 && stateHolder.getState(false) == State.STARTED &&
                    selectorHandler.getStateHolder().getState(false) == State.STARTED) {
                handleSelectedKeys(readyKeys,selectorHandler,serverCtx);
                readyKeys.clear();
            }
            selectorHandler.postSelect(serverCtx);
        }catch(Throwable e){
            handleSelectException(e,selectorHandler, null);
        }finally{
            contexts.offer(serverCtx);
        }
    }

    /**
     * logic performed on the selected keys
     * @param readyKeys
     * @param selectorHandler
     * @param serverCtx
     */
    private void handleSelectedKeys(Set<SelectionKey> readyKeys,SelectorHandler selectorHandler,NIOContext serverCtx){
        for(SelectionKey key:readyKeys) {
          try{
            if (!key.isValid()){
                selectorHandler.getSelectionKeyHandler().close(key);
                continue;
            }            
            final int readyOps = key.readyOps();
            if ((readyOps & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT){
                if (readThreadsCount > 0 &&
                        multiReadThreadSelectorHandler.supportsProtocol(selectorHandler.protocol())) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE, "OP_ACCEPT on " +
                                key + " passed to multi readthread handler. Attachment: " +
                                key.attachment());
                    }
                    multiReadThreadSelectorHandler.onAcceptInterest(key, serverCtx);
                } else {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE,"OP_ACCEPT on "+key +" attachment: "+key.attachment());
                    }
                    selectorHandler.onAcceptInterest(key, serverCtx);
                }
                continue;
            }
            if ((readyOps & SelectionKey.OP_CONNECT) == SelectionKey.OP_CONNECT) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE,"OP_CONNECT on "+key+" attachment: "+key.attachment());
                }
                selectorHandler.onConnectInterest(key, serverCtx);
                continue;
            }

            boolean delegateToWorker = false;
            OpType opType = null;
            boolean skipOpWrite = false;
            // OP_READ will always be processed first, then
            // based on the handleReadWriteConcurrently, the OP_WRITE
            // might be processed just after or during the next
            // Selector.select() invocation.
            if ((readyOps & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE,"OP_READ on "+key+" attachment: "+key.attachment());
                }
                delegateToWorker = selectorHandler.onReadInterest(key,serverCtx);
                if (delegateToWorker) {
                    opType = OpType.OP_READ;
                }
                if (!handleReadWriteConcurrently){
                    skipOpWrite = true;
                }
            }
            // The OP_READ processing might have closed the
            // Selection, hence we must make sure the
            // SelectionKey is still valid.
            if (!skipOpWrite && key.isValid() && (readyOps & SelectionKey.OP_WRITE)
                    == SelectionKey.OP_WRITE) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE,"OP_WRITE on "+key+" attachment: "+key.attachment());
                }
                boolean opWriteDelegate = selectorHandler.onWriteInterest(key,serverCtx);
                delegateToWorker |= opWriteDelegate;
                if (opWriteDelegate) {
                    if (opType == OpType.OP_READ) {
                        opType = OpType.OP_READ_WRITE;
                    } else {
                        opType = OpType.OP_WRITE;
                    }
                }
            }

            if (delegateToWorker){
                NIOContext context = pollContext(key, opType);
                configureContext(context,selectorHandler);
                context.execute(ProtocolChainContextTask.poll());
            }
          }catch(Throwable e){
              handleSelectException(e,selectorHandler,key);
          }
        }
    }


    /**
     *
     * @param e
     * @param selectorHandler
     * @param key
     */
    private void handleSelectException(Throwable e,SelectorHandler selectorHandler,SelectionKey key){
        if (e instanceof ClosedSelectorException) {
            // TODO: This could indicate that the Controller is
            //       shutting down. Hence, we need to handle this Exception
            //       appropriately. Perhaps check the state before logging
            //       what's happening ?
            if (stateHolder.getState() == State.STARTED &&
                    selectorHandler.getStateHolder().getState() == State.STARTED) {
                logger.log(Level.SEVERE, "Selector was unexpectedly closed.");
                notifyException(e);
            } else {
                logger.log(Level.FINE, "doSelect Selector closed");
            }

        }
        else if( e instanceof ClosedChannelException) {
            // Don't use stateLock. This case is not strict
            if (stateHolder.getState() == State.STARTED &&
                    selectorHandler.getStateHolder().getState() == State.STARTED) {
                logger.log(Level.WARNING, "Channel was unexpectedly closed");
                if (key != null){
                    selectorHandler.getSelectionKeyHandler().cancel(key);
                }
                notifyException(e);
            }
        }else{
            try{
                if (key != null){
                    selectorHandler.getSelectionKeyHandler().cancel(key);
                }
                notifyException(e);
                logger.log(Level.SEVERE,"doSelect exception",e);
            } catch (Throwable t2){
                // An unexpected exception occured, most probably caused by
                // a bad logger. Since logger can be externally configurable,
                // just output the exception on the screen and continue the
                // normal execution.
                t2.printStackTrace();
            }
        }
    }


    /**
     * Register a SelectionKey.
     * @param key <tt>SelectionKey</tt> to register
     */
    public void registerKey(SelectionKey key){
        registerKey(key,SelectionKey.OP_READ);
    }


    /**
     * Register a SelectionKey on the first SelectorHandler that was added
     * using the addSelectorHandler().
     * @param key <tt>SelectionKey</tt> to register
     * @param ops - the interest op to register
     */
    public void registerKey(SelectionKey key, int ops){
        registerKey(key, ops, selectorHandlers.peek().protocol());
    }


    /**
     * Register a SelectionKey.
     * @param key <tt>SelectionKey</tt> to register
     * @param ops - the interest op to register
     * @param protocol specified protocol SelectorHandler key should be registered on
     */
    public void registerKey(SelectionKey key, int ops, Protocol protocol){
        if (stateHolder.getState() == State.STOPPED) {
            return;
        }

        getSelectorHandler(protocol).register(key,ops);
    }


    /**
     * Cancel a SelectionKey
     * @param key <tt>SelectionKey</tt> to cancel
     * @deprecated
     */
    public void cancelKey(SelectionKey key){
        if (stateHolder.getState() == State.STOPPED) {
            return;
        }

        SelectorHandler selectorHandler = getSelectorHandler(key.selector());
        if (selectorHandler != null) {
            selectorHandler.getSelectionKeyHandler().cancel(key);
        } else {
            throw new IllegalStateException("SelectionKey is not associated " +
                    "with known SelectorHandler");
        }
    }

    /**
     * Get an instance of a {@link Context}
     * @param key {@link SelectionKey}
     * @return {@link Context}
     */
    /* package */ public Context pollContext(SelectionKey key) {
        return pollContext(key,null);
    }


    /**
     * Get an instance of a {@link NIOContext}
     * @param key {@link SelectionKey}
     * @param opType the current SelectionKey op.
     * @return {@link Context}
     */
    /* package */ public NIOContext pollContext(SelectionKey key, OpType opType) {
        Context context = contexts.poll();
        if (!(context instanceof NIOContext)){
            throw new RuntimeException("Invalid Context instance: "
                    + context.getClass().getName());
        }
        NIOContext ctx = (NIOContext)context;
        ctx.setController(this);
        ctx.setSelectionKey(key);
        if (opType != null) {
            ctx.setCurrentOpType(opType);
        } else {
            if (key != null){
                ctx.configureOpType(key);
            }
        }

        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "pollContext(..) Context : "+ctx);
        }
        return ctx;
    }



    /**
     * Configure the {@link Context}
     * @param context
     * @param selectorHandler
     */
    /* package */ public void configureContext(Context context,SelectorHandler selectorHandler){

        if (!(context instanceof NIOContext)){
            throw new RuntimeException("Invalid Context instance: "
                    + context.getClass().getName());
        }
        NIOContext ctx = (NIOContext)context;

        ctx.setSelectorHandler(selectorHandler);
        ctx.setThreadPool(selectorHandler.getThreadPool());
        ctx.setAsyncQueueReader(selectorHandler.getAsyncQueueReader());
        ctx.setAsyncQueueWriter(selectorHandler.getAsyncQueueWriter());
    }

    /**
     * Return a {@link Context} to its pool if it is not shared.
     *
     * @param ctx - the {@link Context}
     */
    public void returnContext(Context ctx){
        if(ctx instanceof NIOContext && ((NIOContext)ctx).decrementRefCount()>0) {
            return;
        }
       if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "returnContext() Context : "+ctx);
        }
        ctx.recycle();
        contexts.offer(ctx);
    }


    /**
     * Return the current <code>Logger</code> used by this Controller.
     */
    public static Logger logger() {
        return logger;
    }


    /**
     * Set the Logger single instance to use.
     */
    public static void setLogger(Logger l){
        logger = l;
    }

    // ------------------------------------------------------ Handlers ------//


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
     * @deprecated
     * Set the {@link SelectionKeyHandler} to use for managing the life
     * cycle of SelectionKey.
     * Method is deprecated. Use SelectorHandler.setSelectionKeyHandler() instead
     */
    public void setSelectionKeyHandler(SelectionKeyHandler selectionKeyHandler){
        this.selectionKeyHandler = selectionKeyHandler;
    }


    /**
     * @deprecated
     * Return the {@link SelectionKeyHandler}
     * Method is deprecated. Use SelectorHandler.getSelectionKeyHandler() instead
     */
    public SelectionKeyHandler getSelectionKeyHandler(){
        return selectionKeyHandler;
    }


    /**
     * Add a {@link SelectorHandler}
     * @param selectorHandler - the {@link SelectorHandler}
     */
    public void addSelectorHandler(SelectorHandler selectorHandler) {
        selectorHandlers.add(selectorHandler);
        if (stateHolder.getState(false) != null &&
                !State.STOPPED.equals(stateHolder.getState())) {
            addSelectorHandlerOnReadControllers(selectorHandler);
            if (readySelectorHandlerCounter != null) {
                readySelectorHandlerCounter.incrementAndGet();
            }
            if (stoppedSelectorHandlerCounter != null) {
                stoppedSelectorHandlerCounter.incrementAndGet();
            }
            startSelectorHandlerRunner(selectorHandler, true);
        }
    }


    /**
     * Set the first {@link SelectorHandler}
     * @param selectorHandler - the {@link SelectorHandler}
     */
    public void setSelectorHandler(SelectorHandler selectorHandler){
        addSelectorHandler(selectorHandler);
    }


    /**
     * Return the {@link SelectorHandler} associated with the protocol.
     * @param protocol - the {@link Controller.Protocol}
     * @return {@link SelectorHandler}
     */
    public SelectorHandler getSelectorHandler(Protocol protocol){
        for (SelectorHandler selectorHandler: selectorHandlers){
            if (selectorHandler.protocol() == protocol){
                return selectorHandler;
            }
        }
        return null;
    }

    /**
     * Return the {@link SelectorHandler} associated
     * with the {@link Selector}.
     * @param selector - the {@link Selector}
     * @return {@link SelectorHandler}
     */
    public SelectorHandler getSelectorHandler(Selector selector){
        for (SelectorHandler selectorHandler: selectorHandlers){
            if (selectorHandler.getSelector() == selector){
                return selectorHandler;
            }
        }
        return null;
    }

    /**
     * Return the list {@link SelectorHandler}
     * @return {@link ConcurrentLinkedQueue}
     */
    public LinkedTransferQueue getSelectorHandlers(){
        return selectorHandlers;
    }


    /**
     * Shuts down {@link SelectorHandler} and removes it from this
     * {@link Controller} list
     * @param {@link SelectorHandler} to remove
     */
    public void removeSelectorHandler(SelectorHandler selectorHandler) {
        if (selectorHandlers.remove(selectorHandler)) {
            removeSelectorHandlerOnReadControllers(selectorHandler);
            selectorHandler.shutdown();
        }
    }


    /**
     * Return the {@link ExecutorService} (Thread Pool) used by this Controller.
     */
    public ExecutorService getThreadPool() {
        return threadPool;
    }


    /**
     * Set the {@link ExecutorService} (Thread Pool).
     */
    public void setThreadPool(ExecutorService threadPool) {
        this.threadPool = threadPool;
    }


    /**
     * Return the number of Reader threads count.
     */
    public int getReadThreadsCount() {
        return readThreadsCount;
    }


    /**
     * Set the number of Reader threads count.
     */
    public void setReadThreadsCount(int readThreadsCount) {
        this.readThreadsCount = readThreadsCount;
    }


    /**
     * Return the <code>ConnectorHandlerPool</code> used.
     */
    public ConnectorHandlerPool getConnectorHandlerPool() {
        return connectorHandlerPool;
    }


    /**
     * Set the <code>ConnectorHandlerPool</code> used.
     */
    public void setConnectorHandlerPool(ConnectorHandlerPool connectorHandlerPool) {
        this.connectorHandlerPool = connectorHandlerPool;
    }

    // ------------------------------------------------------ Runnable -------//


    /**
     * Execute this Controller.
     */
    public void run() {
        try{
            start();
        } catch(IOException e){
            notifyException(e);
            throw new RuntimeException(e.getCause());
        }
    }

    // -------------------------------------------------------- Copyable ----//


    /**
     * Copy this Controller state to another instance of a Controller.
     */
    public void copyTo(Copyable copy) {
        Controller copyController = (Controller) copy;
        copyController.contexts = contexts;
        copyController.attributes = attributes;
        copyController.instanceHandler = instanceHandler;
        copyController.threadPool = threadPool;
        copyController.readThreadControllers = readThreadControllers;
        copyController.readThreadsCount = readThreadsCount;
        copyController.selectionKeyHandler = selectionKeyHandler;
        copyController.stateHolder = stateHolder;
    }

    // -------------------------------------------------------- Lifecycle ----//

    /**
     * Add controller state listener
     */
    public void addStateListener(ControllerStateListener stateListener) {
        stateListeners.add(stateListener);
    }

    /**
     * Remove controller state listener
     */
    public void removeStateListener(ControllerStateListener stateListener) {
        stateListeners.remove(stateListener);
    }

    /**
     * Notify controller started
     */
    public void notifyStarted() {
        for(ControllerStateListener stateListener : stateListeners) {
            stateListener.onStarted();
        }
    }


    /**
     * Notify controller is ready
     */
    public void notifyReady() {
        if (readySelectorHandlerCounter.decrementAndGet() == 0) {
            for (ControllerStateListener stateListener : stateListeners) {
                stateListener.onReady();
            }
        }
    }


    /**
     * Notify controller stopped
     */
    public void notifyStopped() {
        if (stoppedSelectorHandlerCounter.decrementAndGet() == 0) {
            // Notify internal listeners
            synchronized(stoppedSelectorHandlerCounter) {
                stoppedSelectorHandlerCounter.notifyAll();
            }
        }
    }


    /**
     * Notify exception occured
     */
    protected void notifyException(Throwable e) {
        for(ControllerStateListener stateListener : stateListeners) {
            stateListener.onException(e);
        }
    }


    /**
     * Start the Controller. If the thread pool and/or Handler has not been
     * defined, the default will be used.
     */
    public void start() throws IOException {
        stateHolder.getStateLocker().writeLock().lock();
        boolean isUnlocked = false;
        try {
            if (stateHolder.getState(false) == null ||
                    stateHolder.getState(false) == State.STOPPED) {
                // if selectorHandlers were not set by user explicitly,
                // add TCPSelectorHandler by default
                if (selectorHandlers.isEmpty()) {
                    SelectorHandler selectorHandler = new TCPSelectorHandler();
                    selectorHandlers.add(selectorHandler);
                }

                if (readThreadsCount > 0) {
                    initReadThreads();
                    multiReadThreadSelectorHandler =
                            new RoundRobinSelectorHandler(readThreadControllers);
                }

                stateHolder.setState(State.STARTED, false);
                notifyStarted();

                int selectorHandlerCount = selectorHandlers.size();
                readySelectorHandlerCounter = new AtomicInteger(selectorHandlerCount);
                stoppedSelectorHandlerCounter = new AtomicInteger(selectorHandlerCount);


                Iterator<SelectorHandler> it = selectorHandlers.iterator();
                if (selectorHandlerCount > 1) {
                    for (; it.hasNext() && selectorHandlerCount-- > 0;) {
                        SelectorHandler selectorHandler = it.next();
                        startSelectorHandlerRunner(selectorHandler, true);
                    }
                } else if (it.hasNext()) {
                    SelectorHandler selectorHandler = it.next();
                    stateHolder.getStateLocker().writeLock().unlock();
                    isUnlocked = true;
                    startSelectorHandlerRunner(selectorHandler, false);
                }
            }
        } finally {
            if (!isUnlocked) {
                stateHolder.getStateLocker().writeLock().unlock();
            }
        }

        waitUntilSeletorHandlersStop();

        if (readThreadsCount > 0) {
            multiReadThreadSelectorHandler.shutdown();
            multiReadThreadSelectorHandler = null;

            for (Controller readController : readThreadControllers) {
                try {
                    readController.stop();
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Exception occured when stopping read Controller!", e);
                }
            }

            readThreadControllers = null;
        }

        selectorHandlers.clear();
        threadPool.shutdown();
        attributes = null;

        // Notify Controller listeners
        for (ControllerStateListener stateListener : stateListeners) {
            stateListener.onStopped();
        }
    }


    /**
     * Stop the Controller by canceling all the registered keys.
     */
    public void stop() throws IOException {
        stop(false);
    }


    /**
     * Stop the Controller by canceling all the registered keys.
     * @param isAsync, true if controller should be stopped asynchrounously and control
     *                 returned immediately. If false - control will be returned
     *                 after Controller will be completely stoped.
     */
    public void stop(boolean isAsync) throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        stateHolder.getStateLocker().writeLock().lock();
        try {
            if (stateHolder.getState(false) == State.STOPPED) {
                logger.log(Level.FINE, "Controller is already in stopped state");
                return;
            }

            if (!isAsync) {
                addStateListener(new ControllerStateListenerAdapter() {
                    @Override
                    public void onException(Throwable e) {
                        removeStateListener(this);
                        latch.countDown();
                    }

                    @Override
                    public void onStopped() {
                        removeStateListener(this);
                        latch.countDown();
                    }

                });
            }
            stateHolder.setState(State.STOPPED, false);
        } finally {
            stateHolder.getStateLocker().writeLock().unlock();
        }

        if (!isAsync) {
            try {
                latch.await();
            } catch(InterruptedException e) {}
        }
    }


    /**
     * Pause this {@link Controller} and associated {@link SelectorHandler}s
     */
    public void pause() throws IOException {
        stateHolder.setState(State.PAUSED);
    }


    /**
     * Resume this {@link Controller} and associated {@link SelectorHandler}s
     */
    public void resume() throws IOException {
        if (!State.PAUSED.equals(stateHolder.getState(false))) {
            throw new IllegalStateException("Controller is not in PAUSED state, but: " +
                    stateHolder.getState(false));
        }

        stateHolder.setState(State.STARTED);
    }


    /**
     * Gets this {@link Controller}'s {@link StateHolder}
     * @return {@link StateHolder}
     */
    public StateHolder<State> getStateHolder() {
        return stateHolder;
    }


    /**
     * Initialize the number of ReadThreadController.
     */
    private void initReadThreads() throws IOException {
        // Attributes need to be shared among Controller and its ReadControllers
        if (attributes == null) {
            attributes = new HashMap<String, Object>(2);
        }

        readThreadControllers = new ReadController[readThreadsCount];
        for(int i=0; i<readThreadsCount; i++) {
            ReadController controller = new ReadController();
            copyTo(controller);
            controller.setReadThreadsCount(0);
            readThreadControllers[i] = controller;
        }

        for (SelectorHandler selectorHandler : selectorHandlers) {
            addSelectorHandlerOnReadControllers(selectorHandler);
        }

        for (int i=0; i < readThreadControllers.length; i++) {
            // TODO Get a Thread from a Pool instead.
            new Thread(readThreadControllers[i], "GrizzlyReadController-" + i).start();
        }
    }


    /**
     * Register {@link SelectorHandler} on all read controllers
     * @param selectorHandler
     */
    private void addSelectorHandlerOnReadControllers(SelectorHandler selectorHandler) {
        if (readThreadControllers == null || readThreadsCount == 0) return;

        // Attributes need to be shared among SelectorHandler and its read-copies
        if (selectorHandler.getAttributes() == null) {
            selectorHandler.setAttributes(new HashMap<String, Object>(2));
        }

        for (Controller readController : readThreadControllers) {
            SelectorHandler copySelectorHandler = Cloner.clone(selectorHandler);
            try {
                copySelectorHandler.setSelector(Selector.open());
            } catch(IOException e) {
                logger.log(Level.SEVERE, "Error opening selector!", e);
            }

            readController.addSelectorHandler(copySelectorHandler);
        }
    }


    /**
     * Starts <code>SelectorHandlerRunner</code>
     * @param selectorHandler
     * @param isRunAsync if true - <code>SelectorHandlerRunner</code> will be run
     *          in separate <code>Thread</code>, if false - in current <code>Thread</code>
     */
    private void startSelectorHandlerRunner(SelectorHandler selectorHandler,
            boolean isRunAsync) {

        // check if there is java.nio.Selector already open,
        // if so, just notify the controller onReady() listeners
        if (selectorHandler.getSelector() != null) {
            notifyReady();
        }
        Runnable selectorRunner = new SelectorHandlerRunner(this, selectorHandler);
        if (isRunAsync) {
            // if there are more than 1 selector handler - run it in separate thread
            //@TODO Take Thread from ThreadPool?
            new Thread(selectorRunner, "GrizzlySelectorRunner-" + selectorHandler.protocol()).start();
        } else {
            // else run it in current thread
            selectorRunner.run();
        }
    }


    /**
     * Register {@link SelectorHandler} on all read controllers
     * @param selectorHandler
     */
    private void removeSelectorHandlerOnReadControllers(SelectorHandler selectorHandler) {
        if (readThreadControllers == null) return;

        for (ReadController readController : readThreadControllers) {
            readController.removeSelectorHandlerClone(selectorHandler);
        }
    }


    /**
     * Is this Controller started?
     * @return <code>boolean</code> true / false
     */
    public boolean isStarted() {
        return stateHolder.getState() == State.STARTED;
    }


    // ----------- ConnectorHandlerPool interface implementation ----------- //


    /**
     * Return an instance of a {@link ConnectorHandler} based on the
     * Protocol requested.
     */
    public ConnectorHandler acquireConnectorHandler(Protocol protocol){
        return connectorHandlerPool.acquireConnectorHandler(protocol);
    }


    /**
     * Return a {@link ConnectorHandler} to the pool of ConnectorHandler.
     * Any reference to the returned must not be re-used as that instance
     * can always be acquired again, causing unexpected results.
     */
    public void releaseConnectorHandler(ConnectorHandler connectorHandler){
        connectorHandlerPool.releaseConnectorHandler(connectorHandler);
    }


    /**
     * <tt>true</tt> if OP_ERAD and OP_WRITE can be handled concurrently.
     * If <tt>false</tt>, the Controller will first invoke the OP_READ handler and
     * then invoke the OP_WRITE during the next Selector.select() invocation.
     */
    public boolean isHandleReadWriteConcurrently() {
        return handleReadWriteConcurrently;
    }


    /**
     * <tt>true</tt> if OP_ERAD and OP_WRITE can be handled concurrently.
     * If <tt>false</tt>, the Controller will first invoke the OP_READ handler and
     * then invoke the OP_WRITE during the next Selector.select() invocation.
     */
    public void setHandleReadWriteConcurrently(boolean handleReadWriteConcurrently) {
        this.handleReadWriteConcurrently = handleReadWriteConcurrently;
    }

    /**
     * Method waits until all initialized {@link SelectorHandler}s will
     * not get stopped
     */
    protected void waitUntilSeletorHandlersStop() {
        synchronized(stoppedSelectorHandlerCounter) {
            while(stoppedSelectorHandlerCounter.get() > 0 ||
                    !State.STOPPED.equals(stateHolder.getState())) {
                try {
                    stoppedSelectorHandlerCounter.wait(1000);
                } catch (InterruptedException ex) {
                }
            }
        }
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
     * Return the Controller which is handling the {@link Handler}
     * @param handler The handler (like {@link SelectorHandler})
     * @return The Controller associated with the Handler, or null if not
     * associated.
     */
    public static Controller getHandlerController(Handler handler){
        if (handler instanceof SelectorHandler){
            for (Controller controller: controllers){
                if (controller.getSelectorHandlers().contains(handler)){
                    return controller;
                }
            }
        }
        return null;
    }
}
