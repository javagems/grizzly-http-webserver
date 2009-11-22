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

import com.sun.grizzly.Context.OpType;
import com.sun.grizzly.ContextTask.TaskPool;

/**
 * {@link CallbackHandler} task, which will be executed by 
 * {@link Context}, when Context.execute({@link ContextTask}) 
 * is called.
 * 
 * @author Alexey Stashok
 */
public class CallbackHandlerContextTask extends SelectionKeyContextTask {
    private static final TaskPool<CallbackHandlerContextTask> taskPool = 
            new TaskPool<CallbackHandlerContextTask>() {
        @Override
        public CallbackHandlerContextTask newInstance() {
            return new CallbackHandlerContextTask();
        }
    };
    
    private CallbackHandler callBackHandler;
    
    public static CallbackHandlerContextTask poll() {
        return taskPool.poll();
    }
    
    public static void offer(CallbackHandlerContextTask contextTask) {
        taskPool.offer(contextTask);
    }
    
    protected Object doCall() throws Exception {
        IOEvent ioEvent = context.getIOEvent();
        OpType currentOpType = context.getCurrentOpType();
        
        try {
            if (currentOpType == OpType.OP_READ) {
                callBackHandler.onRead(ioEvent);
            } else if (currentOpType == OpType.OP_WRITE) {
                callBackHandler.onWrite(ioEvent);
            } else if (currentOpType == OpType.OP_CONNECT) {
                callBackHandler.onConnect(ioEvent);
            }
        } finally {
            if (ioEvent != null) {
                // Prevent the CallbackHandler to re-use the context.
                    // TODO: This is still dangerous as the Context might have been
                    // cached by the CallbackHandler.
                ioEvent.attach(null);
                ioEvent = null;
            }
        }

        return null;
    }

    public CallbackHandler getCallBackHandler() {
        return callBackHandler;
    }

    public void setCallBackHandler(CallbackHandler callBackHandler) {
        this.callBackHandler = callBackHandler;
    }

    @Override
    public void recycle() {
        callBackHandler = null;
        super.recycle();
    }

    @Override
    public void offer() {
        offer(this);
    }
}
