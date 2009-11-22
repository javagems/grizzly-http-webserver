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

package com.sun.grizzly.util.net;

import com.sun.grizzly.util.LoggerUtils;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;
// START SJSAS 6439313
import javax.net.ssl.SSLEngine;
// END SJSAS 6439313

/* SSLImplementation:

   Abstract factory and base class for all SSL implementations.

   @author EKR
*/
abstract public class SSLImplementation {
    /**
     * Default Logger.
     */
    private final static Logger logger = LoggerUtils.getLogger();
    
            
    // The default implementations in our search path
    private static final String JSSEImplementationClass=
	"com.sun.grizzly.util.net.jsse.JSSEImplementation";
    
    private static final String[] implementations=
    {        
        JSSEImplementationClass
    };

    public static SSLImplementation getInstance() throws ClassNotFoundException
    {
	for(int i=0;i<implementations.length;i++){
	    try {
               SSLImplementation impl=
		    getInstance(implementations[i]);
		return impl;
	    } catch (Exception e) {
		if(logger.isLoggable(Level.FINE)) 
		    logger.log(Level.FINE,"Error creating " + implementations[i],e);
	    }
	}

	// If we can't instantiate any of these
	throw new ClassNotFoundException("Can't find any SSL implementation");
    }

    public static SSLImplementation getInstance(String className)
	throws ClassNotFoundException
    {
	if(className==null) return getInstance();

	try {
	    // Workaround for the J2SE 1.4.x classloading problem (under Solaris).
	    // Class.forName(..) fails without creating class using new.
	    // This is an ugly workaround. 
	    if( JSSEImplementationClass.equals(className) ) {
		return new com.sun.grizzly.util.net.jsse.JSSEImplementation();
	    }
	    Class clazz=Class.forName(className);
	    return (SSLImplementation)clazz.newInstance();
	} catch (Exception e){
	    if(logger.isLoggable(Level.FINEST))
		logger.log(Level.FINEST,"Error loading SSL Implementation "
			     +className, e);
	    throw new ClassNotFoundException("Error loading SSL Implementation "
				      +className+ " :" +e.toString());
	}
    }

    abstract public String getImplementationName();
    abstract public ServerSocketFactory getServerSocketFactory();
    abstract public SSLSupport getSSLSupport(Socket sock);
    // START SJSAS 6439313
    public abstract SSLSupport getSSLSupport(SSLEngine sslEngine);
    // END SJSAS 6439313
}    
