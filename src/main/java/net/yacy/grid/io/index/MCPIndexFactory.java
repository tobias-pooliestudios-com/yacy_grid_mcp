/**
 *  MCPIndexFactory
 *  Copyright 05.03.2018 by Michael Peter Christen, @orbiterlab
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

package net.yacy.grid.io.index;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.grid.YaCyServices;
import net.yacy.grid.http.APIHandler;
import net.yacy.grid.http.ObjectAPIHandler;
import net.yacy.grid.http.ServiceResponse;
import net.yacy.grid.mcp.Service;
import net.yacy.grid.mcp.api.index.AddService;
import net.yacy.grid.mcp.api.index.CheckService;
import net.yacy.grid.mcp.api.index.CountService;
import net.yacy.grid.mcp.api.index.DeleteService;
import net.yacy.grid.mcp.api.index.ExistService;
import net.yacy.grid.mcp.api.index.QueryService;
import net.yacy.grid.tools.JSONList;
import net.yacy.grid.tools.Logger;

public class MCPIndexFactory implements IndexFactory {

    private final GridIndex index;
    private final String server;
    private final int port;

    public MCPIndexFactory(final GridIndex index, final String server, final int port) {
        this.index = index;
        this.server = server;
        this.port = port;
    }

    @Override
    public String getConnectionURL() {
        return "http://" + this.getHost() + ":" + ((this.hasDefaultPort() ? YaCyServices.mcp.getDefaultPort() : this.getPort()));
    }

    @Override
    public String getHost() {
        return this.server;
    }

    @Override
    public boolean hasDefaultPort() {
        return this.port == -1 || this.port == YaCyServices.mcp.getDefaultPort();
    }

    @Override
    public int getPort() {
        return hasDefaultPort() ? YaCyServices.mcp.getDefaultPort() : this.port;
    }


    @Override
    public Index getIndex() throws IOException {
        final JSONObject params = new JSONObject(true);

        return new Index() {

            private JSONObject getResponse(final APIHandler handler) throws IOException {
                final String protocolhostportstub = MCPIndexFactory.this.getConnectionURL();
                final ServiceResponse sr = handler.serviceImpl(protocolhostportstub, params);
                return sr.getObject();
            }
            private boolean success(final JSONObject response) {
                return response.has(ObjectAPIHandler.SUCCESS_KEY) && response.getBoolean(ObjectAPIHandler.SUCCESS_KEY);
            }
            private void connectMCP(final JSONObject response) {
                if (response.has(ObjectAPIHandler.SERVICE_KEY)) {
                    final String elastic = response.getString(ObjectAPIHandler.SERVICE_KEY);
                    if (MCPIndexFactory.this.index.connectElasticsearch(elastic)) {
                        Logger.info(this.getClass(), "connected MCP index at " + elastic);
                    } else {
                        Logger.error(this.getClass(), "failed to connect MCP index at " + elastic);
                    }
                }
            }
            private IOException handleError(final JSONObject response) {
                if (response.has(ObjectAPIHandler.COMMENT_KEY)) {
                    return new IOException("cannot connect to MCP: " + response.getString(ObjectAPIHandler.COMMENT_KEY));
                }
                return new IOException("bad response from MCP: no success and no comment key");
            }

            @Override
            public IndexFactory checkConnection() throws IOException {
                final String protocolhostportstub = MCPIndexFactory.this.getConnectionURL();
                final APIHandler apiHandler = Service.instance.config.getAPI(CheckService.NAME);
                final ServiceResponse sr = apiHandler.serviceImpl(protocolhostportstub, params);
                final JSONObject response = sr.getObject();
                if (success(response)) {
                    connectMCP(response);
                    return MCPIndexFactory.this;
                } else {
                    throw new IOException("MCP does not respond properly");
                }
            }

            @Override
            public IndexFactory add(final String indexName, final String typeName, final String id, final JSONObject object) throws IOException {
                params.put("index", indexName);
                params.put("type", typeName);
                params.put("id", id);
                params.put("object", object.toString());
                final JSONObject response = getResponse(Service.instance.config.getAPI(AddService.NAME));

                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    return MCPIndexFactory.this;
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public IndexFactory addBulk(final String indexName, final String typeName, final Map<String, JSONObject> objects) throws IOException {
                // We do not introduce a new protocol here. Instead we use the add method.
                // This is not a bad design because grid clients will learn how to use
                // the native elasticsearch interface to do this in a better way.
                for (final Map.Entry<String, JSONObject> entry: objects.entrySet()) {
                    add(indexName, typeName, entry.getKey(), entry.getValue());
                }
                return MCPIndexFactory.this;
            }

            @Override
            public boolean exist(final String indexName, final String id) throws IOException {
                params.put("index", indexName);
                params.put("id", id);
                final JSONObject response = getResponse(Service.instance.config.getAPI(ExistService.NAME));

                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    return response.has("exists") && response.getBoolean("exists");
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public Set<String> existBulk(final String indexName, final Collection<String> ids) throws IOException {
                // We do not introduce a new protocol here. Instead we use the exist method.
                // This is not a bad design because grid clients will learn how to use
                // the native elasticsearch interface to do this in a better way.
                final Set<String> exists = new HashSet<>();
                for (final String id: ids) {
                    if (exist(indexName, id)) exists.add(id);
                }
                return exists;
            }

            @Override
            public long count(final String indexName, final QueryLanguage language, final String query) throws IOException {
                params.put("index", indexName);
                params.put("language", language.name());
                params.put("query", query);
                final JSONObject response = getResponse(Service.instance.config.getAPI(CountService.NAME));

                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    return response.has("count") ? response.getLong("count") : 0;
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public JSONObject query(final String indexName, final String id) throws IOException {
                params.put("index", indexName);
                params.put("id", id);
                final JSONObject response = getResponse(Service.instance.config.getAPI(QueryService.NAME));

                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    if (!response.has("list")) return null;
                    final JSONArray list = response.getJSONArray("list");
                    if (list.length() == 0) return null;
                    return list.getJSONObject(0);
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public Map<String, JSONObject> queryBulk(final String indexName, final Collection<String> ids) throws IOException {
                // We do not introduce a new protocol here. Instead we use the query method.
                // This is not a bad design because grid clients will learn how to use
                // the native elasticsearch interface to do this in a better way.
                final Map<String, JSONObject> result = new HashMap<>();
                for (final String id: ids) {
                    try {
                        final JSONObject j = query(indexName, id);
                        result.put(id, j);
                    } catch (final IOException e) {}
                }
                return result;
            }

            @Override
            public JSONList query(final String indexName, final QueryLanguage language, final String query, final int start, final int count) throws IOException {
                params.put("index", indexName);
                params.put("language", language.name());
                params.put("query", query);
                final JSONObject response = getResponse(Service.instance.config.getAPI(QueryService.NAME));

                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    final JSONList list = new JSONList();
                    if (!response.has("list")) return list;
                    final JSONArray l = response.getJSONArray("list");
                    if (l.length() == 0) return list;
                    for (int i = 0; i < l.length(); i++) list.add(l.getJSONObject(i));
                    return list;
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public JSONObject query(final String indexName, final QueryBuilder queryBuilder, final QueryBuilder postFilter, final Sort sort, final HighlightBuilder hb, final int timezoneOffset, final int from, final int resultCount, final int aggregationLimit, final boolean explain, final WebMapping... aggregationFields) throws IOException {
                throw new IOException("method not implemented"); // TODO implement this!
            }

            @Override
            public boolean delete(final String indexName, final String typeName, final String id) throws IOException {
                params.put("index", indexName);
                params.put("type", typeName);
                params.put("id", id);
                final JSONObject response = getResponse(Service.instance.config.getAPI(DeleteService.NAME));

                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    return response.has("deleted") && response.getBoolean("deleted");
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public long delete(final String indexName, final QueryLanguage language, final String query) throws IOException {
                params.put("index", indexName);
                params.put("language", language.name());
                params.put("query", query);
                final JSONObject response = getResponse(Service.instance.config.getAPI(DeleteService.NAME));

                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    return response.has("count") ? response.getLong("count") : 0;
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public void refresh(final String indexName) {
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException e) {}
            }

            @Override
            public void close() {
            }

        };
    }

    @Override
    public void close() {
        // this is stateless, do nothing
    }

}
