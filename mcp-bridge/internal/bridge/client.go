package bridge

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Credentials struct {
	Authorization string
	DemoActor     string
}

type ActionRequestPayload struct {
	RecommendationID     string `json:"recommendationId"`
	ExpectedStateVersion int64  `json:"expectedStateVersion"`
}

type RuntimeClient interface {
	GetCase(context.Context, Credentials, string) (any, error)
	GetEvidence(context.Context, Credentials, string) (any, error)
	CreateActionRequest(context.Context, Credentials, string, ActionRequestPayload) (any, error)
	Ready(context.Context) error
}

type APIError struct {
	StatusCode int
	Kind       string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("runtime request failed: kind=%s status=%d", e.Kind, e.StatusCode)
}

type HTTPRuntimeClient struct {
	baseURL          *url.URL
	httpClient       *http.Client
	maxRequestBytes  int64
	maxResponseBytes int64
}

func NewHTTPRuntimeClient(cfg Config) *HTTPRuntimeClient {
	// Copy the validated URL so callers cannot mutate the destination after the
	// allowlist check and turn this client into an arbitrary HTTP forwarder.
	baseURL := *cfg.RuntimeBaseURL
	transport := &http.Transport{
		Proxy:                 nil,
		DialContext:           (&net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   32,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: cfg.UpstreamTimeout,
		DisableCompression:    true,
		TLSClientConfig:       &tls.Config{MinVersion: tls.VersionTLS12},
	}
	return &HTTPRuntimeClient{
		baseURL: &baseURL,
		httpClient: &http.Client{
			Transport: transport,
			Timeout:   cfg.UpstreamTimeout,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
		maxRequestBytes:  cfg.MaxRequestBytes,
		maxResponseBytes: cfg.MaxResponseBytes,
	}
}

func (c *HTTPRuntimeClient) GetCase(ctx context.Context, credentials Credentials, caseID string) (any, error) {
	return c.do(ctx, http.MethodGet, "/api/v1/cases/"+caseID, credentials, "", nil)
}

func (c *HTTPRuntimeClient) GetEvidence(ctx context.Context, credentials Credentials, caseID string) (any, error) {
	return c.do(ctx, http.MethodGet, "/api/v1/cases/"+caseID+"/evidence", credentials, "", nil)
}

func (c *HTTPRuntimeClient) CreateActionRequest(ctx context.Context, credentials Credentials, idempotencyKey string, payload ActionRequestPayload) (any, error) {
	return c.do(ctx, http.MethodPost, "/api/v1/action-requests", credentials, idempotencyKey, payload)
}

func (c *HTTPRuntimeClient) Ready(ctx context.Context) error {
	result, err := c.do(ctx, http.MethodGet, "/actuator/health/readiness", Credentials{}, "", nil)
	if err != nil {
		return err
	}
	object, ok := result.(map[string]any)
	if !ok || !strings.EqualFold(fmt.Sprint(object["status"]), "UP") {
		return &APIError{StatusCode: http.StatusServiceUnavailable, Kind: "unavailable"}
	}
	return nil
}

func (c *HTTPRuntimeClient) do(ctx context.Context, method, path string, credentials Credentials, idempotencyKey string, body any) (any, error) {
	endpoint := *c.baseURL
	endpoint.Path = path
	endpoint.RawPath = ""

	var requestBody io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("encode runtime request: %w", err)
		}
		if int64(len(encoded)) > c.maxRequestBytes {
			return nil, errors.New("runtime request exceeds configured size limit")
		}
		requestBody = bytes.NewReader(encoded)
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint.String(), requestBody)
	if err != nil {
		return nil, fmt.Errorf("create runtime request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if credentials.Authorization != "" {
		req.Header.Set("Authorization", credentials.Authorization)
	}
	if credentials.DemoActor != "" {
		req.Header.Set("X-Demo-Actor", credentials.DemoActor)
	}
	if idempotencyKey != "" {
		req.Header.Set("Idempotency-Key", idempotencyKey)
	}

	response, err := c.httpClient.Do(req)
	if err != nil {
		return nil, &APIError{Kind: "unavailable"}
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, c.maxResponseBytes))
		return nil, &APIError{StatusCode: response.StatusCode, Kind: classifyStatus(response.StatusCode)}
	}
	if response.ContentLength > c.maxResponseBytes {
		return nil, &APIError{StatusCode: http.StatusBadGateway, Kind: "response_too_large"}
	}
	mediaType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || (mediaType != "application/json" && !strings.HasSuffix(mediaType, "+json")) {
		return nil, &APIError{StatusCode: http.StatusBadGateway, Kind: "invalid_response"}
	}

	limited := io.LimitReader(response.Body, c.maxResponseBytes+1)
	encoded, err := io.ReadAll(limited)
	if err != nil {
		return nil, &APIError{StatusCode: http.StatusBadGateway, Kind: "invalid_response"}
	}
	if int64(len(encoded)) > c.maxResponseBytes {
		return nil, &APIError{StatusCode: http.StatusBadGateway, Kind: "response_too_large"}
	}
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.UseNumber()
	var result any
	if err := decoder.Decode(&result); err != nil || result == nil {
		return nil, &APIError{StatusCode: http.StatusBadGateway, Kind: "invalid_response"}
	}
	if err := ensureJSONEOF(decoder); err != nil {
		return nil, &APIError{StatusCode: http.StatusBadGateway, Kind: "invalid_response"}
	}
	return result, nil
}

func ensureJSONEOF(decoder *json.Decoder) error {
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return errors.New("response contains trailing JSON")
	}
	return nil
}

func classifyStatus(status int) string {
	switch status {
	case http.StatusBadRequest, http.StatusUnprocessableEntity:
		return "rejected"
	case http.StatusUnauthorized, http.StatusForbidden:
		return "authorization_rejected"
	case http.StatusNotFound:
		return "not_found"
	case http.StatusConflict, http.StatusPreconditionFailed:
		return "state_conflict"
	case http.StatusTooManyRequests:
		return "rate_limited"
	default:
		if status >= 500 {
			return "unavailable"
		}
		return "unexpected_status"
	}
}
