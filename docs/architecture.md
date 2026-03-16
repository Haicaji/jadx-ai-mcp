# System Architecture

Deep dive into JADX-AI-MCP technical design.

## High-Level Design

The system follows a **3-Tier Architecture**:

1.  **Presentation Tier**: LLM Client (Claude, Cherry Studio)
2.  **Application Tier**: MCP Server (Python)
3.  **Data Tier**: JADX Plugin (Java) + JADX Core

```mermaid
block-beta
  columns 3
  LLM_Client(("LLM Client"))
  block:MCP
    MCPServer["MCP Server (Python)"]
    FastMCP["FastMCP Lib"]
  end
  block:Plugin
    Javalin["Javalin Server"]
    JADXAPI["JADX API"]
    GUI["JADX GUI"]
  end

  LLM_Client --> MCPServer
  MCPServer --> Javalin
  Javalin --> JADXAPI
  JADXAPI --> GUI
```

---

## Threading Model

### Python Server (AsyncIO)
- **Single-threaded Event Loop**: Uses `asyncio` for non-blocking I/O
- **HTTP Client**: `httpx.AsyncClient` handles concurrent requests
- **Concurrency**: Can handle multiple MCP tool calls simultaneously

### Java Plugin (Multi-threaded)
- **HTTP Server**: Jetty (via Javalin) uses a thread pool (default: 200 threads)
- **Request Handling**: Each request runs on a worker thread
- **UI Interaction**: **CRITICAL** - All JADX API calls accessing UI/Data must run on **Event Dispatch Thread (EDT)**

**EDT Pattern:**
```java
// Wrong: Direct access from worker thread
String code = javaClass.getCode(); // ConcurrentModificationException

// Correct: Wrap in invokeLater
SwingUtilities.invokeLater(() -> {
    String code = javaClass.getCode(); // Safe
});
```

---

## State Management

### Plugin State (Persistent)
- **Port Configuration**: Stored in `Java Preferences` node `com.zin.jadxaimcp`
- **Session State**: JADX project state (loaded APK, decompilation cache)

### Server State (Transient)
- **Configuration**: Loaded from CLI args at startup
- **Connections**: No persistent connections (HTTP is stateless)

---

## 🔒 Security Model

### Network Security
- **Default Localhost Binding**: Plugin defaults to `127.0.0.1` but can be configured to bind to a custom host/IP.
- **Remote Deployment**: For VM/plugin + host/server setups, configure plugin host in UI and pass same host via `--jadx-host` in `jadx_mcp_server.py`.
- **No Auth**: Relies on OS-level user isolation.

### Input Validation
- **Path Traversal**: Resource paths validated against APK root.
- **SQL Injection**: Not applicable (no SQL database).
- **Code Injection**: Refactoring inputs validated for Java naming rules.

---

## Performance Optimization

### Pagination Strategy
Large APKs can have 10,000+ classes. Returning all at once causes:
1.  **JSON Serialization Overhead**: 10MB+ responses
2.  **Network Latency**: Slow transfer
3.  **Client Timeout**: LLM clients timeout after 60s

**Solution**:
- All list endpoints support `offset` and `count`
- Default page size: 100 items
- Maximum page size: 10,000 items

### Caching
- **JADX Core**: Caches decompiled source automatically
- **Plugin**: Does NOT cache responses (to support real-time refactoring)

---

## Protocol Specifications

### MCP Protocol (JSON-RPC 2.0)
- **Tools**: Exposed as MCP tools
- **Resources**: Not currently used (everything is a tool)
- **Prompts**: Not currently used

### HTTP Protocol (Internal)
- **Method**: GET (mostly)
- **Format**: JSON
- **Status Codes**:
    - `200 OK`: Success
    - `400 Bad Request`: Invalid params
    - `404 Not Found`: Class/Method missing
    - `500 Server Error`: Internal failure

---

## Tech Stack

| Component | Technology | Version | License |
|-----------|------------|---------|---------|
| **Plugin** | Java | 11+ | Apache 2.0 |
| **Server** | Python | 3.10+ | Apache 2.0 |
| **HTTP Server** | Javalin | 5.x | Apache 2.0 |
| **JSON Lib** | Jackson | 2.15+ | Apache 2.0 |
| **MCP Lib** | FastMCP | Latest | MIT |
| **Build** | Gradle | 7.x | Apache 2.0 |
| **Pkg Mgr** | UV | Latest | Apache 2.0 |
