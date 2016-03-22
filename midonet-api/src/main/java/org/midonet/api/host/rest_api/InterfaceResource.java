/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.api.host.rest_api;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.cluster.VendorMediaType;
import org.midonet.api.host.Interface;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RequestScoped
public class InterfaceResource extends AbstractResource {

    private final UUID hostId;

    @Inject
    public InterfaceResource(RestApiConfig config, UriInfo uriInfo,
                             SecurityContext context,
                             DataClient dataClient,
                             @Assisted UUID hostId) {
        super(config, uriInfo, context, dataClient, null);
        this.hostId = hostId;
    }

    /**
     * Handler for listing all the interfaces.
     *
     * @return A list of Interface objects.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<Interface> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.host.Interface> ifConfigs =
                dataClient.interfacesGetByHost(hostId);
        List<Interface> interfaces = new ArrayList<>();

        for (org.midonet.cluster.data.host.Interface ifConfig : ifConfigs) {
            Interface iface = new Interface(hostId, ifConfig);
            iface.setBaseUri(getBaseUri());
            interfaces.add(iface);
        }

        return interfaces;
    }

    /**
     * Handler to getting an interface.
     *
     * @param name       Interface name from the request.
     * @return An Interface object.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{name}")
    @Produces({VendorMediaType.APPLICATION_INTERFACE_JSON,
                  MediaType.APPLICATION_JSON})
    public Interface get(@PathParam("name") String name)
        throws StateAccessException, SerializationException {

        org.midonet.cluster.data.host.Interface ifaceConfig =
                dataClient.interfacesGet(hostId, name);

        if (ifaceConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        Interface iface = new Interface(hostId, ifaceConfig);
        iface.setBaseUri(getBaseUri());

        return iface;
    }
}