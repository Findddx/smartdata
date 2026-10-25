package bridge

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type recordingRuntime struct {
	mu              sync.Mutex
	lastCredentials Credentials
	lastKey         string
	lastAction      ActionRequestPayload
	readyErr        error
}

func (r *recordingRuntime) Ready(_ context.Context) error { return r.readyErr }

func (r *recordingRuntime) GetCase(_ context.Context, credentials Credentials, caseID string) (any, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lastCredentials = credentials
	return map[string]any{"id": caseID, "status": "OPEN"}, nil
}

func (r *recordingRuntime) GetEvidence(_ context.Context, credentials Credentials, caseID string) (any, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lastCredentials = credentials
	return map[string]any{"caseId": caseID, "items": []any{}}, nil
}

func (r *recordingRuntime) CreateActionRequest(_ context.Context, credentials Credentials, key string, payload ActionRequestPayload) (any, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lastCredentials = credentials
	r.lastKey = key
	r.lastAction = payload
	return map[string]any{
		"id": "action-request-1", "definitionId": "reserve_substitute_inventory",
		"definitionVersion": "1.0.0", "state": "AWAITING_APPROVAL", "stateVersion": 2,
		"approvalExpiresAt": "2026-09-08T00:00:00Z",
	}, nil
}

type addHeadersTransport struct {
	base    http.RoundTripper
	headers http.Header
}

func (t addHeadersTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	clone := request.Clone(request.Context())
	clone.Header = request.Header.Clone()
	for name, values := range t.headers {
		for _, value := range values {
			clone.Header.Add(name, value)
		}
	}
	return t.base.RoundTrip(clone)
}

func testMCPConfig() Config {
	return Config{
		AllowedOrigins:        map[string]struct{}{},
		AllowedDemoActors:     map[string]struct{}{"order-specialist": {}},
		MaxRequestBytes:       64 << 10,
		MaxConcurrentRequests: 8,
		AllowDemoAuth:         true,
	}
}

func TestMCPHandlerRejectsSpoofedIdentityHeaders(t *testing.T) {
	handler := NewMCPHandler(testMCPConfig(), &recordingRuntime{}, slog.New(slog.NewTextHandler(io.Discard, nil)), "test")
	request := httptest.NewRequest(http.MethodPost, "http://bridge.test/mcp", nil)
	request.Header.Set("Authorization", "Bearer valid")
	request.Header.Set("X-Tenant-Id", "attacker-tenant")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusBadRequest)
	}
}

func TestMCPHandlerRejectsCrossOriginByDefault(t *testing.T) {
	handler := NewMCPHandler(testMCPConfig(), &recordingRuntime{}, slog.New(slog.NewTextHandler(io.Discard, nil)), "test")
	request := httptest.NewRequest(http.MethodPost, "http://bridge.test/mcp", nil)
	request.Header.Set("Authorization", "Bearer valid")
	request.Header.Set("Origin", "https://attacker.example")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusForbidden)
	}
}

func TestMCPExposesOnlyReadAndCreateRequestTools(t *testing.T) {
	runtime := &recordingRuntime{}
	handler := NewMCPHandler(testMCPConfig(), runtime, slog.New(slog.NewTextHandler(io.Discard, nil)), "test")
	httpServer := httptest.NewServer(handler)
	defer httpServer.Close()

	httpClient := &http.Client{Transport: addHeadersTransport{
		base:    http.DefaultTransport,
		headers: http.Header{"X-Demo-Actor": []string{"order-specialist"}},
	}, Timeout: 5 * time.Second}
	client := mcp.NewClient(&mcp.Implementation{Name: "bridge-test", Version: "1"}, &mcp.ClientOptions{Capabilities: &mcp.ClientCapabilities{}})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	session, err := client.Connect(ctx, &mcp.StreamableClientTransport{
		Endpoint:             httpServer.URL + "/mcp",
		HTTPClient:           httpClient,
		MaxRetries:           -1,
		DisableStandaloneSSE: true,
	}, nil)
	if err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	defer session.Close()

	tools, err := session.ListTools(ctx, nil)
	if err != nil {
		t.Fatalf("ListTools() error = %v", err)
	}
	if len(tools.Tools) != 3 {
		t.Fatalf("tool count = %d, want 3", len(tools.Tools))
	}
	want := map[string]bool{
		"smartdata_get_case":              false,
		"smartdata_list_case_evidence":    false,
		"smartdata_create_action_request": false,
	}
	for _, tool := range tools.Tools {
		if _, ok := want[tool.Name]; !ok {
			t.Fatalf("unexpected tool exposed: %q", tool.Name)
		}
		want[tool.Name] = true
	}
	for name, found := range want {
		if !found {
			t.Errorf("missing tool %q", name)
		}
	}

	result, err := session.CallTool(ctx, &mcp.CallToolParams{
		Name: "smartdata_create_action_request",
		Arguments: map[string]any{
			"recommendationId":     "recommendation-1",
			"expectedStateVersion": 9,
			"idempotencyKey":       "idem-12345678901",
		},
	})
	if err != nil {
		t.Fatalf("CallTool() error = %v", err)
	}
	if result.IsError {
		t.Fatalf("CallTool() returned tool error: %#v", result.Content)
	}
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	if runtime.lastKey != "idem-12345678901" || runtime.lastAction.RecommendationID != "recommendation-1" || runtime.lastAction.ExpectedStateVersion != 9 {
		t.Fatalf("runtime action = %#v, key = %q", runtime.lastAction, runtime.lastKey)
	}
	if runtime.lastCredentials.DemoActor != "order-specialist" {
		t.Fatalf("demo actor = %q", runtime.lastCredentials.DemoActor)
	}
}

func TestHealthHandlerIsSeparateAndMinimal(t *testing.T) {
	response := httptest.NewRecorder()
	NewHealthHandler(&recordingRuntime{}).ServeHTTP(response, httptest.NewRequest(http.MethodGet, "http://health.test/livez", nil))
	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("health response = %d headers=%v", response.Code, response.Header())
	}
}

func TestReadinessFailsWhenRuntimeIsUnavailable(t *testing.T) {
	response := httptest.NewRecorder()
	NewHealthHandler(&recordingRuntime{readyErr: context.DeadlineExceeded}).ServeHTTP(
		response,
		httptest.NewRequest(http.MethodGet, "http://health.test/readyz", nil),
	)
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("readiness response = %d, want %d", response.Code, http.StatusServiceUnavailable)
	}
}
