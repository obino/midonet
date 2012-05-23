/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto.config;

import java.util.UUID;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class ChainNameMgmtConfig {
    public ChainNameMgmtConfig() {
        super();
    }

    public ChainNameMgmtConfig(UUID id) {
        super();
        this.id = id;
    }

    public UUID id;
}
