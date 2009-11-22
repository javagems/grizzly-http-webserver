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
package com.sun.grizzly.util.http;

import java.net.*;
import java.util.*;


/**
 * A mime type map that implements the java.net.FileNameMap interface.
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author Jason Hunter [jch@eng.sun.com]
 */
public class MimeMap implements FileNameMap {

    // Defaults - all of them are "well-known" types,
    // you can add using normal web.xml.
    
    public final static Hashtable<String, String> defaultMap =
        new Hashtable<String, String>(101);
    static {
        defaultMap.put("txt", "text/plain");
        defaultMap.put("html","text/html");
        defaultMap.put("htm", "text/html");
        defaultMap.put("gif", "image/gif");
        defaultMap.put("jpg", "image/jpeg");
        defaultMap.put("jpe", "image/jpeg");
        defaultMap.put("jpeg", "image/jpeg");
		defaultMap.put("java", "text/plain");
        defaultMap.put("body", "text/html");
        defaultMap.put("rtx", "text/richtext");
        defaultMap.put("tsv", "text/tab-separated-values");
        defaultMap.put("etx", "text/x-setext");
        defaultMap.put("ps", "application/x-postscript");
        defaultMap.put("class", "application/java");
        defaultMap.put("csh", "application/x-csh");
        defaultMap.put("sh", "application/x-sh");
        defaultMap.put("tcl", "application/x-tcl");
        defaultMap.put("tex", "application/x-tex");
        defaultMap.put("texinfo", "application/x-texinfo");
        defaultMap.put("texi", "application/x-texinfo");
        defaultMap.put("t", "application/x-troff");
        defaultMap.put("tr", "application/x-troff");
        defaultMap.put("roff", "application/x-troff");
        defaultMap.put("man", "application/x-troff-man");
        defaultMap.put("me", "application/x-troff-me");
        defaultMap.put("ms", "application/x-wais-source");
        defaultMap.put("src", "application/x-wais-source");
        defaultMap.put("zip", "application/zip");
        defaultMap.put("bcpio", "application/x-bcpio");
        defaultMap.put("cpio", "application/x-cpio");
        defaultMap.put("gtar", "application/x-gtar");
        defaultMap.put("shar", "application/x-shar");
        defaultMap.put("sv4cpio", "application/x-sv4cpio");
        defaultMap.put("sv4crc", "application/x-sv4crc");
        defaultMap.put("tar", "application/x-tar");
        defaultMap.put("ustar", "application/x-ustar");
        defaultMap.put("dvi", "application/x-dvi");
        defaultMap.put("hdf", "application/x-hdf");
        defaultMap.put("latex", "application/x-latex");
        defaultMap.put("bin", "application/octet-stream");
        defaultMap.put("oda", "application/oda");
        defaultMap.put("pdf", "application/pdf");
        defaultMap.put("ps", "application/postscript");
        defaultMap.put("eps", "application/postscript");
        defaultMap.put("ai", "application/postscript");
        defaultMap.put("rtf", "application/rtf");
        defaultMap.put("nc", "application/x-netcdf");
        defaultMap.put("cdf", "application/x-netcdf");
        defaultMap.put("cer", "application/x-x509-ca-cert");
        defaultMap.put("exe", "application/octet-stream");
        defaultMap.put("gz", "application/x-gzip");
        defaultMap.put("Z", "application/x-compress");
        defaultMap.put("z", "application/x-compress");
        defaultMap.put("hqx", "application/mac-binhex40");
        defaultMap.put("mif", "application/x-mif");
        defaultMap.put("ief", "image/ief");
        defaultMap.put("tiff", "image/tiff");
        defaultMap.put("tif", "image/tiff");
        defaultMap.put("ras", "image/x-cmu-raster");
        defaultMap.put("pnm", "image/x-portable-anymap");
        defaultMap.put("pbm", "image/x-portable-bitmap");
        defaultMap.put("pgm", "image/x-portable-graymap");
        defaultMap.put("ppm", "image/x-portable-pixmap");
        defaultMap.put("rgb", "image/x-rgb");
        defaultMap.put("xbm", "image/x-xbitmap");
        defaultMap.put("xpm", "image/x-xpixmap");
        defaultMap.put("xwd", "image/x-xwindowdump");
        defaultMap.put("au", "audio/basic");
        defaultMap.put("snd", "audio/basic");
        defaultMap.put("aif", "audio/x-aiff");
        defaultMap.put("aiff", "audio/x-aiff");
        defaultMap.put("aifc", "audio/x-aiff");
        defaultMap.put("wav", "audio/x-wav");
        defaultMap.put("mpeg", "video/mpeg");
        defaultMap.put("mpg", "video/mpeg");
        defaultMap.put("mpe", "video/mpeg");
        defaultMap.put("qt", "video/quicktime");
        defaultMap.put("mov", "video/quicktime");
        defaultMap.put("avi", "video/x-msvideo");
        defaultMap.put("movie", "video/x-sgi-movie");
        defaultMap.put("avx", "video/x-rad-screenplay");
        defaultMap.put("wrl", "x-world/x-vrml");
        defaultMap.put("mpv2", "video/mpeg2");
        
        /* Add XML related MIMEs */
        
        defaultMap.put("xml", "text/xml");
        defaultMap.put("xsl", "text/xml");        
        defaultMap.put("svg", "image/svg+xml");
        defaultMap.put("svgz", "image/svg+xml");
        defaultMap.put("wbmp", "image/vnd.wap.wbmp");
        defaultMap.put("wml", "text/vnd.wap.wml");
        defaultMap.put("wmlc", "application/vnd.wap.wmlc");
        defaultMap.put("wmls", "text/vnd.wap.wmlscript");
        defaultMap.put("wmlscriptc", "application/vnd.wap.wmlscriptc");
    }
    

    private Hashtable<String, String> map = new Hashtable<String, String>();

    public void addContentType(String extn, String type) {
        map.put(extn, type.toLowerCase());
    }

    public Enumeration<String> getExtensions() {
        return map.keys();
    }

    public String getContentType(String extn) {
        String type = map.get(extn.toLowerCase());
        if( type == null ) type = defaultMap.get( extn );
        return type;
    }

    public void removeContentType(String extn) {
        map.remove(extn.toLowerCase());
    }

    /** Get extension of file, without fragment id
     */
    public static String getExtension( String fileName ) {
        // play it safe and get rid of any fragment id
        // that might be there
	int length=fileName.length();
	
        int newEnd = fileName.lastIndexOf('#');
	if( newEnd== -1 ) newEnd=length;
	// Instead of creating a new string.
	//         if (i != -1) {
	//             fileName = fileName.substring(0, i);
	//         }
        int i = fileName.lastIndexOf('.', newEnd );
        if (i != -1) {
             return  fileName.substring(i + 1, newEnd );
        } else {
            // no extension, no content type
            return null;
        }
    }
    
    public String getContentTypeFor(String fileName) {
	String extn=getExtension( fileName );
        if (extn!=null) {
            return getContentType(extn);
        } else {
            // no extension, no content type
            return null;
        }
    }

}
