/**
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 **/

package org.mule.module.gcm;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.mule.api.DefaultMuleException;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.annotations.Configurable;
import org.mule.util.IOUtils;

/**
 * Common properties between GCM and CCS connectors.
 * 
 * @author MuleSoft, Inc.
 */
public abstract class AbstractGcmConnector
{
    protected final Log logger = LogFactory.getLog(getClass());

    /**
     * The Google APIs key.
     */
    @Configurable
    private String apiKey;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    protected String serializeEntityToJson(final Object entity) throws MuleException
    {
        if (entity == null)
        {
            return null;
        }

        try
        {
            return OBJECT_MAPPER.writeValueAsString(entity);
        }
        catch (final IOException ioe)
        {
            throw new DefaultMuleException("Failed to serialize to JSON: " + entity, ioe);
        }
    }

    protected <T> T deserializeJsonToEntity(final TypeReference<T> responseType, final MuleMessage response)
        throws MuleException
    {
        try
        {
            T entity;

            if (logger.isDebugEnabled())
            {
                response.setPayload(IOUtils.toByteArray((InputStream) response.getPayload()));
                entity = OBJECT_MAPPER.<T> readValue((byte[]) response.getPayload(), responseType);
            }
            else
            {
                entity = OBJECT_MAPPER.<T> readValue((InputStream) response.getPayload(), responseType);
            }

            return entity;
        }
        catch (final IOException ioe)
        {
            throw new DefaultMuleException("Failed to deserialize to: " + responseType.getType() + " from: "
                                           + renderMessageAsString(response), ioe);
        }
    }

    protected <T> T deserializeJsonToEntity(final TypeReference<T> responseType, final String response)
        throws MuleException
    {
        try
        {
            return OBJECT_MAPPER.<T> readValue(response, responseType);
        }
        catch (final IOException ioe)
        {
            throw new DefaultMuleException("Failed to deserialize to: " + responseType.getType() + " from: "
                                           + response, ioe);
        }
    }

    protected String renderMessageAsString(final MuleMessage message)
    {
        try
        {
            return message.getPayloadAsString();
        }
        catch (final Exception e)
        {
            return message.toString();
        }
    }

    public String getApiKey()
    {
        return apiKey;
    }

    public void setApiKey(final String apiKey)
    {
        this.apiKey = apiKey;
    }
}
