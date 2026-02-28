package com.zin.jadxaimcp.server;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxaimcp.utils.JadxAIMCPBanner;
import com.zin.jadxaimcp.utils.PaginationUtils;
import com.zin.jadxaimcp.server.routes.*; // MCP tool call's request handlers

public class PluginServer {
    private static final Logger logger = LoggerFactory.getLogger(PluginServer.class);
    // JVM-wide key to store the ServerSocketChannel for cross-classloader shutdown
    private static final String JVM_SERVER_KEY = "jadx-ai-mcp-server-channel";
    private final MainWindow mainWindow;
    private final int port;
    private Javalin app;
    private final PaginationUtils paginationUtils;
    private volatile boolean isRunning = false;

    /**
     * @param mainWindows - The main Jadx window context
     * @param port        - The port to listen on
     */
    public PluginServer(MainWindow mainWindow, int port) {
        this.mainWindow = mainWindow;
        this.port = port;
        this.paginationUtils = new PaginationUtils();
    }

    /**
     * Starts the Javalin HTTP server for the MCP plugin.
     * Before starting, it closes any existing server socket from a previous
     * classloader to prevent port conflicts.
     */
    public void start() {
        try {
            // This solves github issue -> #81
            // Close any existing server socket from a previous classloader
            // (e.g. after Reset Code Cache)
            closeExistingServerSocket();

            // Configure and start Javalin
            // Javalin 7: routes must be registered upfront in the config block
            app = Javalin.create(config -> {
                config.startup.showJavalinBanner = false;
                registerRoutes(config);
            }).start(port);

            // Extract and store the underlying ServerSocketChannel (JDK class) JVM-wide
            // so future classloaders can close it even if the old classloader is broken
            storeServerSocketChannel();

            isRunning = true;

            // Log startup success and banner
            logger.info(JadxAIMCPBanner.banner);
            logger.info("// -------------------- JADX AI MCP PLUGIN -------------------- //");
            logger.info("JADX AI MCP Plugin HTTP Server Started at http://127.0.0.1:" + port + "/");

        } catch (Exception e) {
            logger.error("JADX-AI-MCP Plugin Error: Could not start HTTP Server. Exception: " + e.getMessage(), e);
            isRunning = false;
            // Re-throw to let the main plugin know startup failed
            throw new RuntimeException("Failed to start Javalin Server", e);
        }
    }

    /**
     * Performs graceful shutdown of the Javalin server.
     */
    public void stop() {
        if (app != null) {
            try {
                app.stop();
                System.getProperties().remove(JVM_SERVER_KEY);
                logger.info("JADX-AI-MCP Plugin: HTTP Server Stopped");
            } catch (Exception e) {
                logger.error("JADX-AI-MCP Plugin Error: Error during shutdown: " + e.getMessage(), e);
            } finally {
                app = null;
                isRunning = false;
            }
        }
    }

    /**
     * Extracts the underlying ServerSocketChannel from Javalin/Jetty via reflection
     * and stores it in JVM-wide System properties. This is done at start time when
     * the classloader is valid. The ServerSocketChannel is a JDK class and can be
     * closed later without any dependency on the plugin's classloader.
     *
     * Javalin 7 / Jetty 12 reflection chain:
     * Javalin -> unsafe.jettyInternal -> getServer() -> getConnectors()[0] ->
     * _acceptChannel (private field on ServerConnector)
     */
    private void storeServerSocketChannel() {
        try {
            // Javalin 7: use app.unsafe.jettyInternal to get the Jetty internals
            Object javalinState = app.getClass().getField("unsafe").get(app);
            Object jettyInternal = javalinState.getClass().getField("jettyInternal").get(javalinState);

            // Kotlin property 'server' is accessed via getServer() in Java
            Object server = reflectGetProperty(jettyInternal, "server");
            if (server == null) {
                logger.warn("JADX-AI-MCP Plugin: Could not access Jetty Server instance");
                return;
            }

            Object[] connectors = (Object[]) server.getClass().getMethod("getConnectors").invoke(server);
            if (connectors == null || connectors.length == 0) {
                return;
            }

            // Jetty 12: getTransport() is removed; the ServerSocketChannel is stored
            // in a private '_acceptChannel' field on ServerConnector
            java.nio.channels.ServerSocketChannel channel = reflectGetAcceptChannel(connectors[0]);
            if (channel != null) {
                System.getProperties().put(JVM_SERVER_KEY, channel);
                logger.debug("JADX-AI-MCP Plugin: Stored ServerSocketChannel for cross-classloader cleanup");
            }
        } catch (Exception e) {
            logger.warn("JADX-AI-MCP Plugin: Could not store server socket channel: " + e.getMessage());
        }
    }

    /**
     * Reflectively gets a property from a Kotlin object by trying:
     * 1. getXxx() method (standard Kotlin property accessor)
     * 2. xxx() method (direct method)
     * 3. xxx field (public @JvmField)
     * 4. xxx private field (with setAccessible)
     */
    private Object reflectGetProperty(Object obj, String name) {
        // Try Kotlin getter: getServer()
        String getter = "get" + name.substring(0, 1).toUpperCase() + name.substring(1);
        try {
            return obj.getClass().getMethod(getter).invoke(obj);
        } catch (Exception ignored) {
        }

        // Try direct method: server()
        try {
            return obj.getClass().getMethod(name).invoke(obj);
        } catch (Exception ignored) {
        }

        // Try public field
        try {
            return obj.getClass().getField(name).get(obj);
        } catch (Exception ignored) {
        }

        // Try private/protected field (walk up class hierarchy)
        for (Class<?> cls = obj.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * Extracts the ServerSocketChannel from a Jetty connector by trying:
     * 1. getTransport() method (Jetty 11 and some Jetty 12 builds)
     * 2. _acceptChannel private field (Jetty 12 ServerConnector)
     */
    private java.nio.channels.ServerSocketChannel reflectGetAcceptChannel(Object connector) {
        // Try getTransport() first (may still exist on some Jetty 12 versions)
        try {
            Object transport = connector.getClass().getMethod("getTransport").invoke(connector);
            if (transport instanceof java.nio.channels.ServerSocketChannel) {
                return (java.nio.channels.ServerSocketChannel) transport;
            }
        } catch (Exception ignored) {
        }

        // Jetty 12: walk up class hierarchy looking for _acceptChannel field
        for (Class<?> cls = connector.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("_acceptChannel");
                f.setAccessible(true);
                Object channel = f.get(connector);
                if (channel instanceof java.nio.channels.ServerSocketChannel) {
                    return (java.nio.channels.ServerSocketChannel) channel;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * Closes any existing ServerSocketChannel stored by a previous plugin instance.
     * This directly releases the port at the OS level without needing to call any
     * methods on the broken old classloader's Javalin/Jetty objects.
     */
    private void closeExistingServerSocket() {
        Object stored = System.getProperties().get(JVM_SERVER_KEY);
        if (stored instanceof java.nio.channels.ServerSocketChannel) {
            try {
                java.nio.channels.ServerSocketChannel channel = (java.nio.channels.ServerSocketChannel) stored;
                if (channel.isOpen()) {
                    logger.info("JADX-AI-MCP Plugin: Closing existing server socket from previous classloader...");
                    channel.close();
                    // Wait for the OS to fully release the port
                    Thread.sleep(1000);
                    logger.info("JADX-AI-MCP Plugin: Previous server socket closed, port released.");
                }
            } catch (Exception e) {
                logger.warn("JADX-AI-MCP Plugin: Error closing old server socket: " + e.getMessage());
            } finally {
                System.getProperties().remove(JVM_SERVER_KEY);
            }
        }
    }

    /**
     * @return boolean True if server is running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * @return int The port number the server is configured to listen on
     */
    public int getPort() {
        return port;
    }

    /**
     * Registers all HTTP API endpoints with their route handlers.
     * Javalin 7: routes are registered upfront via the config.routes object.
     *
     * @param routes the Javalin routing configuration from the create() config
     *               block
     */
    private void registerRoutes(JavalinConfig config) {
        // Instantiate Route Controllers
        // Passing 'mainWindow' and 'paginationUtils' to them so they can do their work
        GeneralRoutes generalRoutes = new GeneralRoutes(mainWindow, port, this);
        ClassRoutes classRoutes = new ClassRoutes(mainWindow, paginationUtils);
        MethodRoutes methodRoutes = new MethodRoutes(mainWindow, paginationUtils);
        ResourceRoutes resourceRoutes = new ResourceRoutes(mainWindow);
        RefactoringRoutes refactoringRoutes = new RefactoringRoutes(mainWindow);
        DebugRoutes debugRoutes = new DebugRoutes(mainWindow);
        XrefsRoutes xrefsRoutes = new XrefsRoutes(mainWindow);

        // --- General & Health ---
        config.routes.get("/health", generalRoutes::handleHealth);

        // --- Class & Code Navigation ---
        config.routes.get("/current-class", classRoutes::handleCurrentClass);
        config.routes.get("/all-classes", classRoutes::handleAllClasses);
        config.routes.get("/selected-text", classRoutes::handleSelectedText);
        config.routes.get("/class-source", classRoutes::handleClassSource);
        config.routes.get("/smali-of-class", classRoutes::handleSmaliOfClass);
        config.routes.get("/methods-of-class", classRoutes::handleMethodsOfClass);
        config.routes.get("/fields-of-class", classRoutes::handleFieldsOfClass);
        config.routes.get("/main-application-classes-code", classRoutes::handleMainApplicationClassesCode);
        config.routes.get("/main-application-classes-names", classRoutes::handleMainApplicationClassesNames);
        config.routes.get("/main-activity", classRoutes::handleMainActivity);
        config.routes.get("/search-classes-by-keyword", classRoutes::handleSearchClassesByKeyword);

        // --- Methods ---
        config.routes.get("/method-by-name", methodRoutes::handleMethodByName);
        config.routes.get("/search-method", methodRoutes::handleSearchMethod);

        // --- Xrefs ---
        config.routes.get("/xrefs-to-class", xrefsRoutes::handleXrefsToClass);
        config.routes.get("/xrefs-to-method", xrefsRoutes::handleXrefsToMethod);
        config.routes.get("/xrefs-to-field", xrefsRoutes::handleXrefsToField);

        // --- Resources & Manifest ---
        config.routes.get("/manifest", resourceRoutes::handleManifest);
        config.routes.get("/strings", resourceRoutes::handleStrings);
        config.routes.get("/list-all-resource-files-names", resourceRoutes::handleListAllResourceFilesNames);
        config.routes.get("/get-resource-file", resourceRoutes::handleGetResourceFile);

        // --- Renaming ---
        config.routes.get("/rename-class", refactoringRoutes::handleRenameClass);
        config.routes.get("/rename-method", refactoringRoutes::handleRenameMethod);
        config.routes.get("/rename-field", refactoringRoutes::handleRenameField);
        config.routes.get("/rename-package", refactoringRoutes::handleRenamePackage);
        config.routes.get("/rename-variable", refactoringRoutes::handleRenameVariable);

        // --- Debugging ---
        config.routes.get("/debug/stack-frames", debugRoutes::handleGetStackFrames);
        config.routes.get("/debug/variables", debugRoutes::handleGetVariables);
        config.routes.get("/debug/threads", debugRoutes::handleGetThreads);
    }

}