/*
 * @(#)TenantResource        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.dao.TenantDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RouterResource.TenantRouterResource;

/**
 * Root resource class for tenants.
 *
 * @version        1.6 07 Sept 2011
 * @author         Ryu Ishimoto
 */
@Path("/tenants")
public class TenantResource extends RestResource {
    /*
     * Implements REST API endpoints for tenants.
     */

    /**
     * Router resource locator for tenants
     */
    @Path("/{id}/routers")
    public TenantRouterResource getRouterResource(@PathParam("id") UUID id) {
        return new TenantRouterResource(zookeeperConn, id);
    }
    
    /**
     * Handler for create tenant API call.
     * 
     * @param   tenant  Tenant object.
     * @returns  Response object with 201 status code set if successful.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(Tenant tenant) {
        // Add a new tenant entry into zookeeper.
        if(tenant.getId() == null) {
            tenant.setId(UUID.randomUUID());
        }

        TenantDataAccessor dao = new TenantDataAccessor(zookeeperConn);
        try {
            dao.create(tenant);
        } catch (Exception ex) {
            // TODO: LOG
            System.err.println("Exception = " + ex.getMessage());
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        
        return Response.created(URI.create("/" + tenant.getId())).build();
    }    
}
