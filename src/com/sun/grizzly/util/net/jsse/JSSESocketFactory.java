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
package com.sun.grizzly.util.net.jsse;

import com.sun.grizzly.util.LoggerUtils;
import com.sun.grizzly.util.res.StringManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Vector;
import java.security.KeyStore;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocketFactory;

/*
  1. Make the JSSE's jars available, either as an installed
     extension (copy them into jre/lib/ext) or by adding
     them to the Tomcat classpath.
  2. keytool -genkey -alias tomcat -keyalg RSA
     Use "changeit" as password ( this is the default we use )
 */

/**
 * SSL server socket factory. It _requires_ a valid RSA key and
 * JSSE. 
 *
 * @author Harish Prabandham
 * @author Costin Manolache
 * @author Stefan Freyr Stefansson
 * @author EKR -- renamed to JSSESocketFactory
 */
public abstract class JSSESocketFactory
    extends com.sun.grizzly.util.net.ServerSocketFactory
{
    private static StringManager sm =
        StringManager.getManager("com.sun.grizzly.util.net.jsse.res");

    // defaults
    static String defaultProtocol = "TLS";
    static String defaultAlgorithm = "SunX509";
    static boolean defaultClientAuth = false;
    private static final String defaultKeyPass = "changeit";

    protected static final Logger logger = LoggerUtils.getLogger();

    protected boolean initialized;
    protected boolean clientAuth = false;
    protected SSLServerSocketFactory sslProxy = null;
    protected String[] enabledCiphers;

    public JSSESocketFactory () {
    }
    

    public ServerSocket createSocket (int port)
        throws IOException
    {
        if (!initialized) init();
        ServerSocket socket = sslProxy.createServerSocket(port);
        initServerSocket(socket);
        return socket;
    }
    
    public ServerSocket createSocket (int port, int backlog)
        throws IOException
    {
        if (!initialized) init();
        ServerSocket socket = sslProxy.createServerSocket(port, backlog);
        initServerSocket(socket);
        return socket;
    }
    
    public ServerSocket createSocket (int port, int backlog,
                                      InetAddress ifAddress)
        throws IOException
    {   
        if (!initialized) init();
        ServerSocket socket = sslProxy.createServerSocket(port, backlog,
                                                          ifAddress);
        initServerSocket(socket);
        return socket;
    }
    
    public Socket acceptSocket(ServerSocket socket)
        throws IOException
    {
        SSLSocket asock = null;
        try {
             asock = (SSLSocket)socket.accept();
             asock.setNeedClientAuth(clientAuth);
        } catch (SSLException e){
          throw new SocketException("SSL handshake error" + e.toString());
        }
        return asock;
    }

    public void handshake(Socket sock) throws IOException {
        ((SSLSocket)sock).startHandshake();
    }

    /*
     * Determines the SSL cipher suites to be enabled.
     *
     * @param requestedCiphers Comma-separated list of requested ciphers
     * @param supportedCiphers Array of supported ciphers
     *
     * @return Array of SSL cipher suites to be enabled, or null if none of the
     * requested ciphers are supported
     */
    protected String[] getEnabledCiphers(String requestedCiphers,
                                         String[] supportedCiphers) {

        String[] enabledCiphers = null;

        if (requestedCiphers != null) {
            Vector vec = null;
            String cipher = requestedCiphers;
            int index = requestedCiphers.indexOf(',');
            if (index != -1) {
                int fromIndex = 0;
                while (index != -1) {
                    cipher = requestedCiphers.substring(fromIndex, index).trim();
                    if (cipher.length() > 0) {
                        /*
                         * Check to see if the requested cipher is among the
                         * supported ciphers, i.e., may be enabled
                         */
                        for (int i=0; supportedCiphers != null
                                     && i<supportedCiphers.length; i++) {
                            if (supportedCiphers[i].equals(cipher)) {
                                if (vec == null) {
                                    vec = new Vector();
                                }
                                vec.addElement(cipher);
                                break;
                            }
                        }
                    }
                    fromIndex = index+1;
                    index = requestedCiphers.indexOf(',', fromIndex);
                } // while
                cipher = requestedCiphers.substring(fromIndex);
            }

            if (cipher != null) {
                cipher = cipher.trim();
                if (cipher.length() > 0) {
                    /*
                     * Check to see if the requested cipher is among the
                     * supported ciphers, i.e., may be enabled
                     */
                    for (int i=0; supportedCiphers != null
                                 && i<supportedCiphers.length; i++) {
                        if (supportedCiphers[i].equals(cipher)) {
                            if (vec == null) {
                                vec = new Vector();
                            }
                            vec.addElement(cipher);
                            break;
                        }
                    }
                }
            }           

            if (vec != null) {
                enabledCiphers = new String[vec.size()];
                vec.copyInto(enabledCiphers);
            }
        }

        return enabledCiphers;
    }
     
    /*
     * Gets the SSL server's keystore password.
     */
    protected String getKeystorePassword() {
        String keyPass = (String)attributes.get("keypass");
        if (keyPass == null) {
            keyPass = defaultKeyPass;
        }
        String keystorePass = (String)attributes.get("keystorePass");
        if (keystorePass == null) {
            keystorePass = keyPass;
        }
        return keystorePass;
    }

    /*
     * Gets the SSL server's keystore.
     */
    protected KeyStore getKeystore(String pass)
            throws IOException {

        String keystoreFile = (String)attributes.get("keystore");
        if (logger.isLoggable(Level.FINE)){
            logger.fine("Keystore file= " + keystoreFile);
        }

        String keystoreType = (String)attributes.get("keystoreType");
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Keystore type= " + keystoreType);
        }

        return getStore(keystoreType, keystoreFile, pass);
    }

    /*
     * Gets the SSL server's truststore.
     */
    protected KeyStore getTrustStore() throws IOException {
        KeyStore ts = null;

        String truststore = (String)attributes.get("truststore");
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Truststore file= " + truststore);
        }

        String truststoreType = (String)attributes.get("truststoreType");
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Truststore type= " + truststoreType);
        }

        String truststorePassword = System.getProperty(
                                    "javax.net.ssl.trustStorePassword");
        if (truststorePassword == null) {
            truststorePassword = getKeystorePassword();
        }

        if (truststore != null && truststorePassword != null) {
            ts = getStore(truststoreType, truststore, truststorePassword);
        }

        return ts;
    }

    /*
     * Gets the key- or truststore with the specified type, path, and password.
     */
    private KeyStore getStore(String type, String path, String pass)
            throws IOException {

        KeyStore ks = null;
        InputStream istream = null;
        try {
            ks = KeyStore.getInstance(type);
            if (!("PKCS11".equalsIgnoreCase(type) ||
                    "".equalsIgnoreCase(path))) {
                File keyStoreFile = new File(path);
                if (!keyStoreFile.isAbsolute()) {
                    keyStoreFile = new File(System.getProperty("catalina.base"),
                                            path);
                }
                istream = new FileInputStream(keyStoreFile);
            }

            ks.load(istream, pass.toCharArray());
        } catch (FileNotFoundException fnfe) {
            logger.log(Level.SEVERE,
                    sm.getString("jsse.keystore_load_failed", type, path,
                    fnfe.getMessage()), fnfe); 
            throw fnfe;
        } catch (IOException ioe) {
            logger.log(Level.SEVERE,
                    sm.getString("jsse.keystore_load_failed", type, path,
                    ioe.getMessage()), ioe); 
            throw ioe;      
        } catch(Exception ex) {
            String msg = sm.getString("jsse.keystore_load_failed", type, path,
                    ex.getMessage());
            logger.log(Level.SEVERE, msg, ex);
            throw new IOException(msg);
        } finally {
            if (istream != null) {
                try {
                    istream.close();
                } catch (IOException ioe) {
                    // Do nothing
                }
            }
        }

        return ks;
    }

    /**
     * Reads the keystore and initializes the SSL socket factory.
     *
     * Place holder method to initialize the KeyStore, etc.
     */
    /* SJSAS 6439313
    abstract void init() throws IOException ;
     */
    // START SJSAS 6439313
    public abstract void init() throws IOException ;
    // END SJSAS 6439313

    /*
     * Determines the SSL protocol variants to be enabled.
     *
     * @param socket The socket to get supported list from.
     * @param requestedProtocols Comma-separated list of requested SSL
     * protocol variants
     *
     * @return Array of SSL protocol variants to be enabled, or null if none of
     * the requested protocol variants are supported
     */
    abstract protected String[] getEnabledProtocols(SSLServerSocket socket,
                                                    String requestedProtocols);

    /**
     * Set the SSL protocol variants to be enabled.
     * @param socket the SSLServerSocket.
     * @param protocols the protocols to use.
     */
    abstract protected void setEnabledProtocols(SSLServerSocket socket, 
                                            String [] protocols);

    /**
     * Configures the given SSL server socket with the requested cipher suites,
     * protocol versions, and need for client authentication
     */
    protected void initServerSocket(ServerSocket ssocket) {

        SSLServerSocket socket = (SSLServerSocket) ssocket;

        if (attributes.get("ciphers") != null) {
            socket.setEnabledCipherSuites(enabledCiphers);
        }

        String requestedProtocols = (String) attributes.get("protocols");
        setEnabledProtocols(socket, getEnabledProtocols(socket, 
                                                         requestedProtocols));

        // we don't know if client auth is needed -
        // after parsing the request we may re-handshake
        socket.setNeedClientAuth(clientAuth);
    }

}
