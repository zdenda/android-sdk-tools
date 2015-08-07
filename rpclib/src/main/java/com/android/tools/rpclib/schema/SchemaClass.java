/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * THIS FILE WAS GENERATED BY codergen. EDIT WITH CARE.
 */
package com.android.tools.rpclib.schema;

import org.jetbrains.annotations.NotNull;

import com.android.tools.rpclib.binary.BinaryClass;
import com.android.tools.rpclib.binary.BinaryID;
import com.android.tools.rpclib.binary.BinaryObject;
import com.android.tools.rpclib.binary.Decoder;
import com.android.tools.rpclib.binary.Encoder;
import com.android.tools.rpclib.binary.Namespace;

import java.io.IOException;

public final class SchemaClass implements BinaryObject {
    //<<<Start:Java.ClassBody:1>>>
    BinaryID mTypeID;
    String mPackage;
    String mName;
    boolean mExported;
    Field[] mFields;
    BinaryObject[] mMetadata;

    // Constructs a default-initialized {@link SchemaClass}.
    public SchemaClass() {}


    public BinaryID getTypeID() {
        return mTypeID;
    }

    public SchemaClass setTypeID(BinaryID v) {
        mTypeID = v;
        return this;
    }

    public String getPackage() {
        return mPackage;
    }

    public SchemaClass setPackage(String v) {
        mPackage = v;
        return this;
    }

    public String getName() {
        return mName;
    }

    public SchemaClass setName(String v) {
        mName = v;
        return this;
    }

    public boolean getExported() {
        return mExported;
    }

    public SchemaClass setExported(boolean v) {
        mExported = v;
        return this;
    }

    public Field[] getFields() {
        return mFields;
    }

    public SchemaClass setFields(Field[] v) {
        mFields = v;
        return this;
    }

    public BinaryObject[] getMetadata() {
        return mMetadata;
    }

    public SchemaClass setMetadata(BinaryObject[] v) {
        mMetadata = v;
        return this;
    }

    @Override @NotNull
    public BinaryClass klass() { return Klass.INSTANCE; }

    private static final byte[] IDBytes = {-15, -85, -82, -49, -61, 35, -8, 101, -95, -21, -32, 58, -95, -82, -77, -85, 119, -80, 87, -17, };
    public static final BinaryID ID = new BinaryID(IDBytes);

    static {
        Namespace.register(ID, Klass.INSTANCE);
    }
    public static void register() {}
    //<<<End:Java.ClassBody:1>>>
    public enum Klass implements BinaryClass {
        //<<<Start:Java.KlassBody:2>>>
        INSTANCE;

        @Override @NotNull
        public BinaryID id() { return ID; }

        @Override @NotNull
        public BinaryObject create() { return new SchemaClass(); }

        @Override
        public void encode(@NotNull Encoder e, BinaryObject obj) throws IOException {
            SchemaClass o = (SchemaClass)obj;
            e.id(o.mTypeID);
            e.string(o.mPackage);
            e.string(o.mName);
            e.bool(o.mExported);
            e.uint32(o.mFields.length);
            for (int i = 0; i < o.mFields.length; i++) {
                e.value(o.mFields[i]);
            }
            e.uint32(o.mMetadata.length);
            for (int i = 0; i < o.mMetadata.length; i++) {
                e.object(o.mMetadata[i]);
            }
        }

        @Override
        public void decode(@NotNull Decoder d, BinaryObject obj) throws IOException {
            SchemaClass o = (SchemaClass)obj;
            o.mTypeID = d.id();
            o.mPackage = d.string();
            o.mName = d.string();
            o.mExported = d.bool();
            o.mFields = new Field[d.uint32()];
            for (int i = 0; i <o.mFields.length; i++) {
                o.mFields[i] = new Field();
                d.value(o.mFields[i]);
            }
            o.mMetadata = new BinaryObject[d.uint32()];
            for (int i = 0; i <o.mMetadata.length; i++) {
                o.mMetadata[i] = d.object();
            }
        }
        //<<<End:Java.KlassBody:2>>>
    }
}
