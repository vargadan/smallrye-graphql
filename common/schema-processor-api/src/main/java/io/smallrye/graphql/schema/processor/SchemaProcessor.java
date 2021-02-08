package io.smallrye.graphql.schema.processor;

import java.util.Iterator;
import java.util.ServiceLoader;

import org.jboss.jandex.IndexView;

import io.smallrye.graphql.schema.model.Schema;

/**
 * Document the idea of SchemaProcessor here
 */
public interface SchemaProcessor {

    void processSchema(Schema schema, IndexView index, boolean isInBuild);

    static SchemaProcessor getInstance() {
        ServiceLoader<SchemaProcessor> serviceLoader = ServiceLoader.load(SchemaProcessor.class);
        Iterator<SchemaProcessor> it = serviceLoader.iterator();
        if (it.hasNext()) {
            SchemaProcessor instance = it.next();
            if (it.hasNext()) {
                // log that there are more implementations to be found
                // since they can potentially modify the schema we keep the number of applied modifiers to one
                // if some wish to chain them they have to do it within their own implementation
                return instance;
            }
            return instance;
        } else {
            // log that there were no implementation found
            return null;
        }
    }
}
