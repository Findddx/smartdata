package bridge

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

var safeIdentifier = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`)

type GetCaseInput struct {
	CaseID string `json:"caseId" jsonschema:"stable SmartData case identifier"`
}

type GetEvidenceInput struct {
	CaseID string `json:"caseId" jsonschema:"stable SmartData case identifier"`
}

type CreateActionRequestInput struct {
	RecommendationID     string `json:"recommendationId" jsonschema:"approved recommendation identifier used to propose an action"`
	ExpectedStateVersion int64  `json:"expectedStateVersion" jsonschema:"case state version the recommendation was derived from"`
	IdempotencyKey       string `json:"idempotencyKey" jsonschema:"stable caller-generated idempotency key, 16 to 128 safe characters"`
}

func NewMCPHandler(cfg Config, runtime RuntimeClient, logger *slog.Logger, version string) http.Handler {
	server := mcp.NewServer(
		&mcp.Implementation{Name: "smartdata-order-fulfillment", Version: version},
		&mcp.ServerOptions{Logger: logger},
	)
	closedWorld := false
	nonDestructive := false

	mcp.AddTool[GetCaseInput, any](server, &mcp.Tool{
		Name:        "smartdata_get_case",
		Title:       "读取履约异常案件",
		Description: "Read one order-fulfillment exception case, including its current state and traceable summary. This tool never changes business state.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(ctx context.Context, request *mcp.CallToolRequest, input GetCaseInput) (*mcp.CallToolResult, any, error) {
		if !safeIdentifier.MatchString(input.CaseID) {
			return toolError("invalid_input", "caseId is invalid"), nil, nil
		}
		credentials, err := requestCredentials(request, cfg)
		if err != nil {
			return toolError("authorization_rejected", "valid caller authorization is required"), nil, nil
		}
		result, err := runtime.GetCase(ctx, credentials, input.CaseID)
		if err != nil {
			logger.WarnContext(ctx, "MCP runtime call failed", "tool", "smartdata_get_case", "error_kind", safeErrorKind(err))
			return runtimeToolError(err), nil, nil
		}
		minimal, err := minimizeCaseResponse(result)
		if err != nil {
			return toolError("invalid_response", "runtime case response did not match the minimized MCP contract"), nil, nil
		}
		return nil, minimal, nil
	})

	mcp.AddTool[GetEvidenceInput, any](server, &mcp.Tool{
		Name:        "smartdata_list_case_evidence",
		Title:       "读取案件证据",
		Description: "Read provenance-bearing evidence for one order-fulfillment exception case. This tool never changes business state.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(ctx context.Context, request *mcp.CallToolRequest, input GetEvidenceInput) (*mcp.CallToolResult, any, error) {
		if !safeIdentifier.MatchString(input.CaseID) {
			return toolError("invalid_input", "caseId is invalid"), nil, nil
		}
		credentials, err := requestCredentials(request, cfg)
		if err != nil {
			return toolError("authorization_rejected", "valid caller authorization is required"), nil, nil
		}
		result, err := runtime.GetEvidence(ctx, credentials, input.CaseID)
		if err != nil {
			logger.WarnContext(ctx, "MCP runtime call failed", "tool", "smartdata_list_case_evidence", "error_kind", safeErrorKind(err))
			return runtimeToolError(err), nil, nil
		}
		minimal, err := minimizeEvidenceResponse(result)
		if err != nil {
			return toolError("invalid_response", "runtime evidence response did not match the minimized MCP contract"), nil, nil
		}
		return nil, minimal, nil
	})

	mcp.AddTool[CreateActionRequestInput, any](server, &mcp.Tool{
		Name:        "smartdata_create_action_request",
		Title:       "创建动作申请",
		Description: "Create an approval-gated ActionRequest from an existing recommendation. This never executes the action; execution remains behind policy checks and human approval.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: false, DestructiveHint: &nonDestructive, IdempotentHint: true, OpenWorldHint: &closedWorld},
	}, func(ctx context.Context, request *mcp.CallToolRequest, input CreateActionRequestInput) (*mcp.CallToolResult, any, error) {
		if !safeIdentifier.MatchString(input.RecommendationID) || input.ExpectedStateVersion < 0 || !validIdempotencyKey(input.IdempotencyKey) {
			return toolError("invalid_input", "recommendationId, expectedStateVersion, or idempotencyKey is invalid"), nil, nil
		}
		if request.Extra != nil {
			if headerKey := strings.TrimSpace(request.Extra.Header.Get("Idempotency-Key")); headerKey != "" && headerKey != input.IdempotencyKey {
				return toolError("invalid_input", "idempotency header and argument do not match"), nil, nil
			}
		}
		credentials, err := requestCredentials(request, cfg)
		if err != nil {
			return toolError("authorization_rejected", "valid caller authorization is required"), nil, nil
		}
		payload := ActionRequestPayload{RecommendationID: input.RecommendationID, ExpectedStateVersion: input.ExpectedStateVersion}
		result, err := runtime.CreateActionRequest(ctx, credentials, input.IdempotencyKey, payload)
		if err != nil {
			logger.WarnContext(ctx, "MCP runtime call failed", "tool", "smartdata_create_action_request", "error_kind", safeErrorKind(err))
			return runtimeToolError(err), nil, nil
		}
		minimal, err := minimizeActionRequestResponse(result)
		if err != nil {
			return toolError("invalid_response", "runtime action request response did not match the minimized MCP contract"), nil, nil
		}
		return nil, minimal, nil
	})

	sdkHandler := mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return server }, &mcp.StreamableHTTPOptions{
		Stateless:                    true,
		JSONResponse:                 true,
		PropagateRequestCancellation: true,
		Logger:                       logger,
	})
	return middlewareChain(cfg, logger, sdkHandler)
}

func NewHealthHandler(runtime RuntimeClient) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /livez", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "alive"})
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, request *http.Request) {
		ctx, cancel := context.WithTimeout(request.Context(), 2*time.Second)
		defer cancel()
		if err := runtime.Ready(ctx); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "not_ready"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	})
	return securityHeaders(mux)
}

func middlewareChain(cfg Config, logger *slog.Logger, next http.Handler) http.Handler {
	limited := concurrentLimit(cfg.MaxConcurrentRequests, next)
	authenticated := authenticate(cfg, limited)
	originChecked := validateRequestOrigin(cfg.AllowedOrigins, authenticated)
	bodyLimited := limitRequestBody(cfg.MaxRequestBytes, originChecked)
	logged := accessLog(logger, bodyLimited)
	return recoverPanics(logger, securityHeaders(exactMCPPath(logged)))
}

func exactMCPPath(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/mcp" || r.URL.RawQuery != "" {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "not_found"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}

func limitRequestBody(maxBytes int64, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Body != nil {
			r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
		}
		next.ServeHTTP(w, r)
	})
}

func concurrentLimit(maxConcurrent int, next http.Handler) http.Handler {
	semaphore := make(chan struct{}, maxConcurrent)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case semaphore <- struct{}{}:
			defer func() { <-semaphore }()
			next.ServeHTTP(w, r)
		default:
			w.Header().Set("Retry-After", "1")
			writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "busy"})
		}
	})
}

func validateRequestOrigin(allowed map[string]struct{}, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := strings.TrimSpace(r.Header.Get("Origin"))
		if origin != "" {
			parsed, err := url.Parse(origin)
			canonical := ""
			if err == nil && parsed.User == nil && parsed.Path == "" && parsed.RawQuery == "" && parsed.Fragment == "" {
				canonical = strings.ToLower(parsed.Scheme + "://" + parsed.Host)
			}
			if _, ok := allowed[canonical]; !ok || canonical == "" {
				writeJSON(w, http.StatusForbidden, map[string]string{"error": "origin_rejected"})
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func authenticate(cfg Config, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Tenant-Id") != "" || r.Header.Get("X-Roles") != "" {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "identity_headers_forbidden"})
			return
		}
		authorizations := r.Header.Values("Authorization")
		demoActors := r.Header.Values("X-Demo-Actor")
		if len(authorizations) > 1 || len(demoActors) > 1 || (len(authorizations) == 1 && len(demoActors) == 1) {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "ambiguous_credentials"})
			return
		}
		if len(authorizations) == 1 {
			if !validBearer(authorizations[0]) {
				writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid_authorization"})
				return
			}
			next.ServeHTTP(w, r)
			return
		}
		if !cfg.AllowDemoAuth || len(demoActors) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "authorization_required"})
			return
		}
		actor := strings.ToLower(strings.TrimSpace(demoActors[0]))
		if _, ok := cfg.AllowedDemoActors[actor]; !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid_demo_actor"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func validBearer(value string) bool {
	if len(value) < 8 || len(value) > 8192 || strings.ContainsAny(value, "\r\n") {
		return false
	}
	parts := strings.SplitN(value, " ", 2)
	return len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") && strings.TrimSpace(parts[1]) != ""
}

func requestCredentials(request *mcp.CallToolRequest, cfg Config) (Credentials, error) {
	if request == nil || request.Extra == nil {
		return Credentials{}, errors.New("transport credentials unavailable")
	}
	authorization := request.Extra.Header.Get("Authorization")
	if validBearer(authorization) {
		return Credentials{Authorization: authorization}, nil
	}
	if cfg.AllowDemoAuth {
		actor := strings.ToLower(strings.TrimSpace(request.Extra.Header.Get("X-Demo-Actor")))
		if _, ok := cfg.AllowedDemoActors[actor]; ok {
			return Credentials{DemoActor: actor}, nil
		}
	}
	return Credentials{}, errors.New("authorization required")
}

func validIdempotencyKey(value string) bool {
	return len(value) >= 16 && len(value) <= 128 && safeIdentifier.MatchString(value)
}

func toolError(code, message string) *mcp.CallToolResult {
	payload, _ := json.Marshal(map[string]string{"code": code, "message": message})
	return &mcp.CallToolResult{IsError: true, Content: []mcp.Content{&mcp.TextContent{Text: string(payload)}}}
}

func runtimeToolError(err error) *mcp.CallToolResult {
	kind := safeErrorKind(err)
	message := "SmartData runtime is temporarily unavailable"
	switch kind {
	case "rejected":
		message = "The SmartData runtime rejected the request"
	case "authorization_rejected":
		message = "The SmartData runtime rejected caller authorization"
	case "not_found":
		message = "The requested SmartData resource was not found"
	case "state_conflict":
		message = "The case state or idempotency record has changed; refresh before retrying"
	case "rate_limited":
		message = "The SmartData runtime is rate limiting requests"
	case "response_too_large", "invalid_response":
		message = "The SmartData runtime returned an invalid response"
	}
	return toolError(kind, message)
}

func safeErrorKind(err error) string {
	var apiError *APIError
	if errors.As(err, &apiError) && apiError.Kind != "" {
		return apiError.Kind
	}
	return "unavailable"
}

type responseRecorder struct {
	http.ResponseWriter
	status int
}

// Unwrap lets net/http.ResponseController reach optional interfaces such as
// Flusher without the logging wrapper weakening Streamable HTTP behavior.
func (r *responseRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

func (r *responseRecorder) WriteHeader(status int) {
	if r.status == 0 {
		r.status = status
	}
	r.ResponseWriter.WriteHeader(status)
}

func accessLog(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		requestID := randomRequestID()
		w.Header().Set("X-Request-Id", requestID)
		recorder := &responseRecorder{ResponseWriter: w}
		next.ServeHTTP(recorder, r)
		status := recorder.status
		if status == 0 {
			status = http.StatusOK
		}
		logger.InfoContext(r.Context(), "MCP request", "request_id", requestID, "method", r.Method, "path", r.URL.Path, "status", status, "duration_ms", time.Since(started).Milliseconds())
	})
}

func recoverPanics(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				logger.ErrorContext(r.Context(), "MCP handler panic recovered", "request_id", w.Header().Get("X-Request-Id"))
				writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "internal_error"})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func randomRequestID() string {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "unavailable"
	}
	return hex.EncodeToString(bytes)
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
