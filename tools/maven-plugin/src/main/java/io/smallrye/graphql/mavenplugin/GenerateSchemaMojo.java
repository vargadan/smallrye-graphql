package io.smallrye.graphql.mavenplugin;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.JarIndexer;
import org.jboss.jandex.Result;

import graphql.schema.GraphQLSchema;
import io.smallrye.graphql.bootstrap.Bootstrap;
import io.smallrye.graphql.bootstrap.Config;
import io.smallrye.graphql.execution.SchemaPrinter;
import io.smallrye.graphql.schema.SchemaBuilder;
import io.smallrye.graphql.schema.model.Schema;
import io.smallrye.graphql.schema.processor.SchemaProcessor;

@Mojo(name = "generate-schema", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class GenerateSchemaMojo extends AbstractMojo {

    /**
     * Destination file where to output the schema.
     * If no path is specified, the schema will be printed to the log.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated/schema.graphql", property = "destination")
    private String destination;

    /**
     * Scan project's dependencies for GraphQL model classes too. This is off by default, because
     * it takes a relatively long time, so turn this on only if you know that part of your
     * model is located inside dependencies.
     */
    @Parameter(defaultValue = "false", property = "includeDependencies")
    private boolean includeDependencies;

    /**
     * When you include dependencies, we only look at compile and system scopes (by default)
     * You can change that here.
     * Valid options are: compile, provided, runtime, system, test, import
     */
    @Parameter(defaultValue = "compile,system", property = "includeDependenciesScopes")
    private List<String> includeDependenciesScopes;

    /**
     * When you include dependencies, we only look at jars (by default)
     * You can change that here.
     */
    @Parameter(defaultValue = "jar", property = "includeDependenciesTypes")
    private List<String> includeDependenciesTypes;

    @Parameter(defaultValue = "false", property = "includeScalars")
    private boolean includeScalars;

    @Parameter(defaultValue = "false", property = "includeDirectives")
    private boolean includeDirectives;

    @Parameter(defaultValue = "false", property = "includeSchemaDefinition")
    private boolean includeSchemaDefinition;

    @Parameter(defaultValue = "false", property = "includeIntrospectionTypes")
    private boolean includeIntrospectionTypes;

    @Parameter(defaultValue = "Default", property = "typeAutoNameStrategy")
    private String typeAutoNameStrategy;

    @Parameter(defaultValue = "${project}", required = true)
    private MavenProject mavenProject;

    @Parameter(property = "project.compileClasspathElements", required = true, readonly = true)
    private List<String> classpath;

    @Parameter(defaultValue = "false", property = "skip")
    private boolean skip;

    /**
     * Compiled classes of the project.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "classesDir")
    private File classesDir;

    @Override
    public void execute() throws MojoExecutionException {
        if (!skip) {
            ClassLoader classLoader = getClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);

            IndexView index = createIndex();
            String schema = generateSchema(index);
            if (schema != null) {
                write(schema);
            } else {
                getLog().warn("No Schema generated. Check that your code contains the MicroProfile GraphQL Annotations");
            }
        }
    }

    private IndexView createIndex() throws MojoExecutionException {
        IndexView moduleIndex;
        try {
            moduleIndex = indexModuleClasses();
        } catch (IOException e) {
            throw new MojoExecutionException("Can't compute index", e);
        }
        if (includeDependencies) {
            List<IndexView> indexes = new ArrayList<>();
            indexes.add(moduleIndex);
            for (Object a : mavenProject.getArtifacts()) {
                Artifact artifact = (Artifact) a;
                if (includeDependenciesScopes.contains(artifact.getScope())
                        && includeDependenciesTypes.contains(artifact.getType())) {
                    getLog().debug("Indexing file " + artifact.getFile());
                    try {
                        Result result = JarIndexer.createJarIndex(artifact.getFile(), new Indexer(),
                                false, false, false);
                        indexes.add(result.getIndex());
                    } catch (Exception e) {
                        getLog().error("Can't compute index of " + artifact.getFile().getAbsolutePath() + ", skipping", e);
                    }
                }
            }
            return CompositeIndex.create(indexes);
        } else {
            return moduleIndex;
        }
    }

    // index the classes of this Maven module
    private Index indexModuleClasses() throws IOException {
        Indexer indexer = new Indexer();
        try (Stream<Path> classesDirStream = Files.walk(classesDir.toPath())) {
            List<Path> classFiles = classesDirStream
                    .filter(path -> path.toString().endsWith(".class"))
                    .collect(Collectors.toList());
            for (Path path : classFiles) {
                indexer.index(Files.newInputStream(path));
            }
        }
        return indexer.complete();
    }

    private String generateSchema(IndexView index) {
        Config config = new Config() {
            @Override
            public boolean isIncludeScalarsInSchema() {
                return includeScalars;
            }

            @Override
            public boolean isIncludeDirectivesInSchema() {
                return includeDirectives;
            }

            @Override
            public boolean isIncludeSchemaDefinitionInSchema() {
                return includeSchemaDefinition;
            }

            @Override
            public boolean isIncludeIntrospectionTypesInSchema() {
                return includeIntrospectionTypes;
            }
        };

        Schema internalSchema = SchemaBuilder.build(index);
        processSchema(internalSchema, index);
        GraphQLSchema graphQLSchema = Bootstrap.bootstrap(internalSchema, config);
        if (graphQLSchema != null) {
            return new SchemaPrinter(config).print(graphQLSchema);
        }
        return null;
    }

    private void processSchema(Schema schema, IndexView index) {
        SchemaProcessor schemaProcessor = SchemaProcessor.getInstance();
        if (schemaProcessor != null) {
            getLog().info("Processing schema with : " + schemaProcessor.getClass().getName());
            schemaProcessor.processSchema(schema, index, true);
        } else {
            getLog().info("There is no " + SchemaProcessor.class.getSimpleName() + " installed on classpath.");
        }
    }

    private void write(String schema) throws MojoExecutionException {
        try {
            if (destination == null || destination.isEmpty()) {
                // no destination file specified => print to stdout
                getLog().info(schema);
            } else {
                Path path = new File(destination).toPath();
                path.toFile().getParentFile().mkdirs();
                Files.write(path, schema.getBytes(),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                getLog().info("Wrote the schema to " + path.toAbsolutePath().toString());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Can't write the result", e);
        }
    }

    private ClassLoader getClassLoader() {
        Set<URL> urls = new HashSet<>();

        for (String element : classpath) {
            try {
                urls.add(new File(element).toURI().toURL());
            } catch (MalformedURLException ex) {
                throw new RuntimeException(ex);
            }
        }

        return URLClassLoader.newInstance(
                urls.toArray(new URL[0]),
                Thread.currentThread().getContextClassLoader());

    }
}
