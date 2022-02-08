/**
 *  PeerStorage
 *  Copyright 28.1.2017 by Michael Peter Christen, @orbiterlab
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

package net.yacy.grid.io.assets;

import java.io.File;
import java.io.IOException;

public class PeerStorage implements Storage<byte[]> {

    private StorageFactory<byte[]> factory;
    
    public PeerStorage(boolean deleteafterread, File basePath) {
        this.factory = basePath == null ? null : new FilesystemStorageFactory(deleteafterread, basePath);
    }

    @Override
    public void checkConnection() throws IOException {
        if (this.factory == null) throw new IOException("peer storage not enabled");
    }
    
    @Override
    public StorageFactory<byte[]> store(String path, byte[] asset) throws IOException {
        if (this.factory == null) throw new IOException("peer storage not enabled");
        return this.factory.getStorage().store(path, asset);
    }

    @Override
    public Asset<byte[]> load(String path) throws IOException {
        if (this.factory == null) throw new IOException("peer storage not enabled");
        return this.factory.getStorage().load(path);
    }

    @Override
    public void close() {
        if (this.factory != null) this.factory.close();
        this.factory = null;
    }
}
