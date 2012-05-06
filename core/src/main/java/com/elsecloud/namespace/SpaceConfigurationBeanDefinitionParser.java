/**
 * Copyright (C) 2011 Andrey Borisov <aandrey.borisov@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.elsecloud.namespace;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser;
import org.springframework.util.StringUtils;
import org.w3c.dom.Element;

import com.elsecloud.api.ClientSpaceConfiguration;
import com.elsecloud.api.SpaceConfiguration;

/**
 * class it responsible for parsing jspace configuration namespace
 * 
 * @since 0.1
 */
class SpaceConfigurationBeanDefinitionParser extends AbstractSingleBeanDefinitionParser {
    private final boolean serverSide;

    SpaceConfigurationBeanDefinitionParser(final boolean serverSide) {
        super();
        this.serverSide = serverSide;
    }

    @Override
    protected void doParse(final Element element,
                           final BeanDefinitionBuilder builder) {
        String group = element.getAttribute( "group" );
        String mappingContext = element.getAttribute( "mappingContext" );
        String conversionService = element.getAttribute( "conversionService" );
        String kryo = element.getAttribute( "kryo" );
        String jChannel = element.getAttribute( "jChannel" );

        if ( StringUtils.hasText( group ) )
            builder.addPropertyValue( "group", group );
        if ( StringUtils.hasText( mappingContext ) )
            builder.addPropertyReference( "mappingContext", mappingContext );
        if ( StringUtils.hasText( conversionService ) )
            builder.addPropertyReference( "conversionService", conversionService );
        if ( StringUtils.hasText( kryo ) )
            builder.addPropertyReference( "kryo", kryo );
        if ( StringUtils.hasText( jChannel ) )
            builder.addPropertyReference( "jChannel", jChannel );
    }

    @Override
    protected Class<?> getBeanClass(final Element element) {
        return serverSide ? SpaceConfiguration.class : ClientSpaceConfiguration.class;
    }
}
