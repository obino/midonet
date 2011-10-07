package com.midokura.midolman.util;

public interface Cache {

    void set(String key, String value);
    String get(String key);
    String getAndTouch(String key);
    int getExpirationSeconds();
}
