package bridge

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func testRuntimeClient(serverURL string, maxResponse int64) *HTTPRuntimeClient {
	baseURL, _ := url.Parse(serverURL)
	return NewHTTPRuntimeClient(Config{
		RuntimeBaseURL:   baseURL,
		UpstreamTimeout:  2 * time.Second,
		MaxRequestBytes:  4096,
		MaxResponseBytes: maxResponse,
	})
}

func TestHTTPRuntimeClientForwardsOnlyExplicitCredentials(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/cases/case-42/evidence" {
			t.Errorf("path = %q", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer test-token" {
			t.Errorf("Authorization = %q", got)
		}
		if got := r.Header.Get("X-Tenant-Id"); got != "" {
			t.Errorf("X-Tenant-Id must not be forwarded: %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `[{"id":"evidence-1"}]`)
	}))
	defer server.Close()

	client := testRuntimeClient(server.URL, 1024)
	result, err := client.GetEvidence(context.Background(), Credentials{Authorization: "Bearer test-token"}, "case-42")
	if err != nil {
		t.Fatalf("GetEvidence() error = %v", err)
	}
	if items, ok := result.([]any); !ok || len(items) != 1 {
		t.Fatalf("result = %#v", result)
	}
}

func TestHTTPRuntimeClientCreatesOnlyActionRequest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/action-requests" {
			t.Errorf("request = %s %s", r.Method, r.URL.Path)
		}
		if got := r.Header.Get("Idempotency-Key"); got != "idem-12345678901" {
			t.Errorf("Idempotency-Key = %q", got)
		}
		if got := r.Header.Get("X-Demo-Actor"); got != "order-specialist" {
			t.Errorf("X-Demo-Actor = %q", got)
		}
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if len(body) != 2 || body["recommendationId"] != "rec-1" || body["expectedStateVersion"] != float64(7) {
			t.Errorf("unexpected body: %#v", body)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"id":"action-request-1","status":"AWAITING_APPROVAL"}`)
	}))
	defer server.Close()

	client := testRuntimeClient(server.URL, 1024)
	_, err := client.CreateActionRequest(context.Background(), Credentials{DemoActor: "order-specialist"}, "idem-12345678901", ActionRequestPayload{
		RecommendationID: "rec-1", ExpectedStateVersion: 7,
	})
	if err != nil {
		t.Fatalf("CreateActionRequest() error = %v", err)
	}
}

func TestHTTPRuntimeClientDoesNotFollowRedirectWithBearer(t *testing.T) {
	var targetRequests atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		targetRequests.Add(1)
	}))
	defer target.Close()
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Redirect(w, &http.Request{}, target.URL, http.StatusTemporaryRedirect)
	}))
	defer origin.Close()

	client := testRuntimeClient(origin.URL, 1024)
	_, err := client.GetCase(context.Background(), Credentials{Authorization: "Bearer sensitive"}, "case-1")
	if err == nil {
		t.Fatal("GetCase() succeeded through redirect")
	}
	if got := targetRequests.Load(); got != 0 {
		t.Fatalf("redirect target received %d requests", got)
	}
}

func TestHTTPRuntimeClientBoundsAndValidatesResponse(t *testing.T) {
	tests := []struct {
		name        string
		contentType string
		body        string
		wantKind    string
	}{
		{name: "too large", contentType: "application/json", body: `{"value":"` + strings.Repeat("x", 256) + `"}`, wantKind: "response_too_large"},
		{name: "not json", contentType: "text/html", body: `<h1>secret</h1>`, wantKind: "invalid_response"},
		{name: "trailing json", contentType: "application/json", body: `{}` + `{}`, wantKind: "invalid_response"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", test.contentType)
				_, _ = io.WriteString(w, test.body)
			}))
			defer server.Close()
			client := testRuntimeClient(server.URL, 64)
			_, err := client.GetCase(context.Background(), Credentials{}, "case-1")
			apiError, ok := err.(*APIError)
			if !ok || apiError.Kind != test.wantKind {
				t.Fatalf("error = %#v, want kind %q", err, test.wantKind)
			}
		})
	}
}
