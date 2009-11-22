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

package com.sun.grizzly.filter;

import com.sun.grizzly.Context;
import com.sun.grizzly.Controller;
import com.sun.grizzly.ProtocolFilter;
import com.sun.grizzly.util.OutputWriter;
import com.sun.grizzly.util.WorkerThread;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.util.logging.Level;

/**
 * Simple echo filter
 *
 * @author Alexey Stashok
 */
public class EchoFilter implements ProtocolFilter {
    public boolean execute(Context ctx) throws IOException {
        final WorkerThread workerThread = ((WorkerThread)Thread.currentThread());
        ByteBuffer buffer = workerThread.getByteBuffer();
        buffer.flip();
        if (buffer.hasRemaining()) {
            // Depending on protocol perform echo
            SelectableChannel channel = ctx.getSelectionKey().channel();
            try {
                if (ctx.getProtocol() == Controller.Protocol.TCP) { // TCP case
                    OutputWriter.flushChannel(channel, buffer);
                } else if (ctx.getProtocol() == Controller.Protocol.UDP) {  // UDP case
                    DatagramChannel datagramChannel = (DatagramChannel) channel;
                    SocketAddress address = (SocketAddress) 
                            ctx.getAttribute(ReadFilter.UDP_SOCKETADDRESS);
                    OutputWriter.flushChannel(datagramChannel, address, buffer);
                }
            } catch (IOException ex) {
                // Store incoming data in byte[]
                byte[] data = new byte[buffer.remaining()];
                int position = buffer.position();
                buffer.get(data);
                buffer.position(position);
                
                Controller.logger().log(Level.WARNING, 
                        "Exception. Echo \"" + new String(data) + "\"");
                throw ex;
            }
        }
        
        buffer.clear();
        return false;
    }
    
    public boolean postExecute(Context ctx) throws IOException {
        return true;
    }
}
