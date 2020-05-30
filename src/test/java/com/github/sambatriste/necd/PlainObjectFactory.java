package com.github.sambatriste.necd;

import nablarch.core.repository.di.ComponentFactory;

public class PlainObjectFactory implements ComponentFactory<PlainObject> {

    private String name;

    @Override
    public PlainObject createObject() {
        PlainObject plainObject = new PlainObject();
        plainObject.setName(this.name);
        return plainObject;
    }

    public void setName(String name) {
        this.name = name;
    }
}
