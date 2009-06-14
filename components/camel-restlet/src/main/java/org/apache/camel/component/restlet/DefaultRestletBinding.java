/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.restlet;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import javax.xml.transform.dom.DOMSource;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.converter.jaxp.StringSource;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.spi.HeaderFilterStrategyAware;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.CharacterSet;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;

/**
 * Default Restlet binding implementation
 *
 * @version $Revision$
 */
public class DefaultRestletBinding implements RestletBinding, HeaderFilterStrategyAware {
    private static final Log LOG = LogFactory.getLog(DefaultRestletBinding.class);
    private HeaderFilterStrategy headerFilterStrategy;

    public void populateExchangeFromRestletRequest(Request request, Exchange exchange) throws Exception {
        Message inMessage = exchange.getIn();

        // extract headers from restlet
        for (Map.Entry<String, Object> entry : request.getAttributes().entrySet()) {
            if (!headerFilterStrategy.applyFilterToExternalHeaders(entry.getKey(), entry.getValue(), exchange)) {
                inMessage.setHeader(entry.getKey(), entry.getValue());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Populate exchange from Restlet request header: " 
                            + entry.getKey() + " value: " + entry.getValue());
                }
            }
        }
        
        // copy query string to header
        String query = request.getResourceRef().getQuery();
        if (query != null) {
            inMessage.setHeader(RestletConstants.RESTLET_QUERY_STRING, query);
        }
        
        // copy URI to header
        inMessage.setHeader(Exchange.HTTP_URI, request.getResourceRef().getIdentifier(true));
        
        // copy HTTP method to header
        inMessage.setHeader(Exchange.HTTP_METHOD, request.getMethod().toString());

        if (!request.isEntityAvailable()) {
            return;
        }

        Form form = new Form(request.getEntity());
        for (Map.Entry<String, String> entry : form.getValuesMap().entrySet()) {
            // extract body added to the form as the key which has null value
            if (entry.getValue() == null) {
                inMessage.setBody(entry.getKey());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Populate exchange from Restlet request body: " + entry.getValue());
                }
            } else {
                if (!headerFilterStrategy.applyFilterToExternalHeaders(entry.getKey(), entry.getValue(), exchange)) {
                    inMessage.setHeader(entry.getKey(), entry.getValue());
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Populate exchange from Restlet request user header: "
                                + entry.getKey() + " value: " + entry.getValue());
                    }
                }
            }
        }
    }

    public void populateRestletRequestFromExchange(Request request, Exchange exchange) {
        request.setReferrerRef("camel-restlet");
        String body = exchange.getIn().getBody(String.class);
        Form form = new Form();
        // add the body as the key in the form with null value
        form.add(body, null);
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("Populate Restlet request from exchange body: " + body);
        }
        
        // login and password are filtered by header filter strategy
        String login = exchange.getIn().getHeader(RestletConstants.RESTLET_LOGIN, String.class);
        String password = exchange.getIn().getHeader(RestletConstants.RESTLET_PASSWORD, String.class);
          
        if (login != null && password != null) {
            ChallengeResponse authentication = new ChallengeResponse(ChallengeScheme.HTTP_BASIC, login, password);
            request.setChallengeResponse(authentication);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Basic HTTP Authentication has been applied");
            }
        }
        
        for (Map.Entry<String, Object> entry : exchange.getIn().getHeaders().entrySet()) {
            if (!headerFilterStrategy.applyFilterToCamelHeaders(entry.getKey(), entry.getValue(), exchange)) {
                if (entry.getKey().startsWith("org.restlet.")) {
                    // put the org.restlet headers in attributes
                    request.getAttributes().put(entry.getKey(), entry.getValue());
                } else {
                    // put the user stuff in the form
                    form.add(entry.getKey(), entry.getValue().toString());   
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Populate Restlet request from exchange header: " 
                            + entry.getKey() + " value: " + entry.getValue());
                }
            }
        }
        
        request.setEntity(form.getWebRepresentation());
    }

    public void populateRestletResponseFromExchange(Exchange exchange, Response response) {
        
        Message out;
        if (exchange.isFailed()) {
            // 500 for internal server error which can be overridden by response code in header
            response.setStatus(Status.valueOf(500));
            if (exchange.hasFault()) {
                out = exchange.getFault();
            } else {
                // print exception as message and stacktrace
                Exception t = exchange.getException();
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                t.printStackTrace(pw);
                response.setEntity(sw.toString(), MediaType.TEXT_PLAIN);
                return;
            }
        } else {
            out = exchange.getOut();
        }
             
        // get content type
        MediaType mediaType = out.getHeader(RestletConstants.RESTLET_MEDIA_TYPE, MediaType.class);
        if (mediaType == null) {
            Object body = out.getBody();
            mediaType = MediaType.TEXT_PLAIN;
            if (body instanceof String) {
                mediaType = MediaType.TEXT_PLAIN;
            } else if (body instanceof StringSource || body instanceof DOMSource) {
                mediaType = MediaType.TEXT_XML;
            }
        }
                
        // get response code
        Integer responseCode = out.getHeader(RestletConstants.RESTLET_RESPONSE_CODE, Integer.class);
        if (responseCode != null) {
            response.setStatus(Status.valueOf(responseCode));
        }

        for (Map.Entry<String, Object> entry : out.getHeaders().entrySet()) {
            if (!headerFilterStrategy.applyFilterToCamelHeaders(entry.getKey(), entry.getValue(), exchange)) {
                response.getAttributes().put(entry.getKey(), entry.getValue());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Populate Restlet response from exchange header: " 
                            + entry.getKey() + " value: " + entry.getValue());
                }
            }
        }
        
        String text = out.getBody(String.class);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Populate Restlet response from exchange body: " + text);
        }
        response.setEntity(text, mediaType);
        
        if (exchange.getProperty(Exchange.CHARSET_NAME) != null) {
            response.getEntity().setCharacterSet(CharacterSet.valueOf(exchange.getProperty(Exchange.CHARSET_NAME, 
                                                                                           String.class)));
        } 
    }

    public void populateExchangeFromRestletResponse(Exchange exchange, Response response) throws Exception {
        
        for (Map.Entry<String, Object> entry : response.getAttributes().entrySet()) {
            if (!headerFilterStrategy.applyFilterToExternalHeaders(entry.getKey(), entry.getValue(), exchange)) {
                exchange.getOut().setHeader(entry.getKey(), entry.getValue());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Populate exchange from Restlet response header: " 
                            + entry.getKey() + " value: " + entry.getValue());
                }
            }
        }

        String text = response.getEntity().getText();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Populate exchange from Restlet response: " + text);
        }
        
        if (exchange.getPattern().isOutCapable()) {
            exchange.getOut().setBody(text);
        } else {
            throw new RuntimeCamelException("Exchange is incapable of receiving response: " + exchange + " with pattern: " + exchange.getPattern());
        }
    }

    public HeaderFilterStrategy getHeaderFilterStrategy() {
        return headerFilterStrategy;
    }

    public void setHeaderFilterStrategy(HeaderFilterStrategy strategy) {
        headerFilterStrategy = strategy;
    }
}
