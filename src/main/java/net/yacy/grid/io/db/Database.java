/**
 *  Database
 *  Copyright 15.01.2017 by Michael Peter Christen, @orbiterlab
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.io.db;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;

/**
 * Grid-Service key-value store Interface for grid-wide properties
 * 
 *
 */
public interface Database extends Closeable {
    
    public long size(String serviceName, String tableName) throws IOException;
    
    public void write(String serviceName, String tableName, String id, String value) throws IOException;

    public String read(String serviceName, String tableName, final String id) throws IOException;

    public boolean exist(String serviceName, String tableName, final String id) throws IOException;

    public boolean delete(String serviceName, String tableName, final String id) throws IOException;
    
    public Iterator<String> ids(String serviceName, String tableName) throws IOException;
    
}
