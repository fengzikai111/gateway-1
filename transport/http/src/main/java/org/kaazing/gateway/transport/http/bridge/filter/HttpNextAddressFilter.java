/**
 * Copyright (c) 2007-2014 Kaazing Corporation. All rights reserved.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.kaazing.gateway.transport.http.bridge.filter;

import static java.lang.String.format;
import static org.kaazing.gateway.resource.address.ResourceAddress.NEXT_PROTOCOL;
import static org.kaazing.gateway.resource.address.ResourceAddress.TRANSPORT;
import static org.kaazing.gateway.transport.BridgeSession.LOCAL_ADDRESS;
import static org.kaazing.gateway.transport.http.HttpHeaders.HEADER_HOST;
import static org.kaazing.gateway.transport.http.HttpHeaders.HEADER_X_NEXT_PROTOCOL;
import static org.kaazing.gateway.transport.http.HttpStatus.CLIENT_NOT_FOUND;
import static org.kaazing.gateway.transport.http.HttpVersion.HTTP_1_1;

import java.net.URI;

import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.kaazing.gateway.resource.address.ResourceAddress;
import org.kaazing.gateway.resource.address.ResourceAddressFactory;
import org.kaazing.gateway.resource.address.ResourceOptions;
import org.kaazing.gateway.transport.Bindings;
import org.kaazing.gateway.transport.Bindings.Binding;
import org.kaazing.gateway.transport.http.HttpBindings.HttpBinding;
import org.kaazing.gateway.transport.http.bridge.HttpRequestMessage;
import org.kaazing.gateway.transport.http.bridge.HttpResponseMessage;
import org.kaazing.mina.core.future.DefaultWriteFutureEx;
import org.kaazing.mina.core.write.DefaultWriteRequestEx;

public class HttpNextAddressFilter extends HttpFilterAdapter<IoSession> {

    private ResourceAddressFactory addressFactory;
    private Bindings<HttpBinding> bindings;

    public void setResourceAddressFactory(ResourceAddressFactory addressFactory) {
        this.addressFactory = addressFactory;
    }
    
    public void setBindings(Bindings<HttpBinding> bindings) {
        this.bindings = bindings;
    }

    @Override
    public void onPreAdd(IoFilterChain parent, String name, NextFilter nextFilter) throws Exception {
        if (addressFactory == null) {
            throw new NullPointerException("addressFactory");
        }
        if (bindings == null) {
            throw new NullPointerException("pathMapsByAddress");
        }
    }

    @Override
    protected void httpRequestReceived(NextFilter nextFilter, IoSession session, HttpRequestMessage httpRequest)
            throws Exception {
//        GL.debug("http", getClass().getSimpleName() + " request received.");

        // unique query parameter is used only to fully defeat caching at client
        // where cache-control headers were ignored by some http client stacks
        httpRequest.removeParameter(".kn");

        ResourceAddress candidateAddress = createCandidateAddress(session, httpRequest);
        Binding binding = bindings.getBinding(candidateAddress);
        if (binding == null) {
            candidateAddress = createAltCandidateAddress(session, httpRequest);
            binding = bindings.getBinding(candidateAddress);
        }
        ResourceAddress localAddress = (binding != null) ? binding.bindAddress() : null;
        if (localAddress != null) {
            httpRequest.setExternalURI(candidateAddress.getExternalURI());
            httpRequest.setLocalAddress(localAddress);
            super.httpRequestReceived(nextFilter, session, httpRequest);
        }
        else {
            HttpResponseMessage httpResponse = new HttpResponseMessage();
            httpResponse.setVersion(HTTP_1_1);
            httpResponse.setStatus(CLIENT_NOT_FOUND);

            DefaultWriteFutureEx writeFuture = new DefaultWriteFutureEx(session);
            DefaultWriteRequestEx writeRequest = new DefaultWriteRequestEx(httpResponse, writeFuture);
            nextFilter.filterWrite(session, writeRequest);

            // Close after write - HttpPersistence filter moved to application-side of this filter.
            writeRequest.getFuture().addListener(new IoFutureListener<WriteFuture>() {
                @Override
                public void operationComplete(WriteFuture future) {
                    IoSession session = future.getSession();

                    // close on flush at server
                    session.close(false);
                }

            });
        }
    }

    private ResourceAddress createCandidateAddress(IoSession session, HttpRequestMessage httpRequest) {

        URI requestURI = httpRequest.getRequestURI();
        String authority = httpRequest.getHeader(HEADER_HOST);
        String scheme = httpRequest.isSecure() ? "https" : "http";

        // handle missing Host header
        if (authority == null) {
            return null;
        }

        URI candidateURI = URI.create(format("%s://%s/", scheme, authority)).resolve(requestURI);
        String nextProtocol = httpRequest.getHeader(HEADER_X_NEXT_PROTOCOL);

        ResourceAddress transport = LOCAL_ADDRESS.get(session);
        if (transport == null) {
            throw new NullPointerException("transport");
        }

        ResourceOptions options = ResourceOptions.FACTORY.newResourceOptions();
        options.setOption(NEXT_PROTOCOL, nextProtocol);
        options.setOption(TRANSPORT, transport);

        return addressFactory.newResourceAddress(candidateURI, options);
    }

    private ResourceAddress createAltCandidateAddress(IoSession session, HttpRequestMessage httpRequest) {

        URI requestURI = httpRequest.getRequestURI();
        String authority = httpRequest.getHeader(HEADER_HOST);
        String scheme = httpRequest.isSecure() ? "https" : "http";

        // handle missing Host header
        if (authority == null) {
            return null;
        }

        URI candidateURI = URI.create(format("%s://%s/", scheme, authority)).resolve(requestURI);
        String nextProtocol = null;

        ResourceAddress transport = LOCAL_ADDRESS.get(session);
        if (transport == null) {
            throw new NullPointerException("transport");
        }

        ResourceOptions options = ResourceOptions.FACTORY.newResourceOptions();
        options.setOption(NEXT_PROTOCOL, nextProtocol);
        options.setOption(TRANSPORT, transport);

        return addressFactory.newResourceAddress(candidateURI, options);
    }

}
