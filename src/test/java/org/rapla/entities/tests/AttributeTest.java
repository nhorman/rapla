/*--------------------------------------------------------------------------*
 | Copyright (C) 2014 Christopher Kohlhaas                                  |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/
package org.rapla.entities.tests;
import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.rapla.entities.dynamictype.Attribute;
import org.rapla.entities.dynamictype.AttributeType;
import org.rapla.entities.dynamictype.DynamicType;
import org.rapla.entities.dynamictype.DynamicTypeAnnotations;
import org.rapla.facade.ClientFacade;
import org.rapla.facade.ModificationModule;
import org.rapla.facade.QueryModule;
import org.rapla.facade.UpdateModule;

import junit.framework.Test;
import junit.framework.TestSuite;
import org.rapla.framework.logger.RaplaBootstrapLogger;
import org.rapla.test.util.RaplaTestCase;

@RunWith(JUnit4.class)
public class AttributeTest  {
    ModificationModule modificationMod;
    QueryModule queryMod;
    UpdateModule updateMod;
    DynamicType type;
    

    @Before
    public void setUp() throws Exception {
        ClientFacade facade = RaplaTestCase.createSimpleSimpsonsWithHomer();
        queryMod = facade;
        modificationMod = facade;
        updateMod = facade;
    }

    @org.junit.Test
    public void testAnnotations() throws Exception {
        type = modificationMod.newDynamicType(DynamicTypeAnnotations.VALUE_CLASSIFICATION_TYPE_RESOURCE);
        type.setKey("test-type");
        Attribute a1 = modificationMod.newAttribute(AttributeType.STRING);
        a1.setKey("test-attribute");
        a1.setAnnotation("expected-rows", "5");
        type.getName().setName("en", "test-type");
        type.addAttribute( a1 );
        modificationMod.store( type );

        DynamicType type2 = queryMod.getDynamicType("test-type");
        Attribute a2 = type2.getAttribute("test-attribute");
        
        Assert.assertEquals(a1, a2);
        Assert.assertEquals("default-annotation", a2.getAnnotation("not-defined-ann", "default-annotation"));
        Assert.assertEquals("expected-rows", a2.getAnnotationKeys()[0]);
        Assert.assertEquals("5", a2.getAnnotation("expected-rows"));
        
    }
}




