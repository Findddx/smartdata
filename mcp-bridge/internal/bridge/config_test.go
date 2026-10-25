package bridge

import (
	"strings"
	"testing"
)

func env(values map[string]string) LookupEnv {
	return func(key string) (string, bool) {
		value, ok := values[key]
		return value, ok
	}
}

func validConfigEnv() map[string]string {
	return map[string]string{
		"SMARTDATA_RUNTIME_BASE_URL":      "https://runtime.internal",
		"SMARTDATA_RUNTIME_ALLOWED_HOSTS": "runtime.internal",
	}
}

func TestLoadConfigSecureDefaults(t *testing.T) {
	cfg, err := LoadConfig(env(validConfigEnv()))
	if err != nil {
		t.Fatalf("LoadConfig() error = %v", err)
	}
	if cfg.RuntimeBaseURL.String() != "https://runtime.internal" {
		t.Fatalf("runtime URL = %q", cfg.RuntimeBaseURL)
	}
	if cfg.AllowDemoAuth || cfg.AllowInsecureUpstream {
		t.Fatal("insecure local-only modes must default to false")
	}
	if cfg.ListenAddr != "127.0.0.1:8082" || cfg.HealthListenAddr != "127.0.0.1:8083" {
		t.Fatalf("unexpected listener defaults: %q %q", cfg.ListenAddr, cfg.HealthListenAddr)
	}
}

func TestLoadConfigRejectsRuntimeOutsideAllowlist(t *testing.T) {
	values := validConfigEnv()
	values["SMARTDATA_RUNTIME_ALLOWED_HOSTS"] = "other.internal"
	_, err := LoadConfig(env(values))
	if err == nil || !strings.Contains(err.Error(), "not in SMARTDATA_RUNTIME_ALLOWED_HOSTS") {
		t.Fatalf("LoadConfig() error = %v, want allowlist error", err)
	}
}

func TestLoadConfigRejectsHTTPByDefault(t *testing.T) {
	values := validConfigEnv()
	values["SMARTDATA_RUNTIME_BASE_URL"] = "http://runtime.internal"
	_, err := LoadConfig(env(values))
	if err == nil || !strings.Contains(err.Error(), "must use https") {
		t.Fatalf("LoadConfig() error = %v, want HTTPS error", err)
	}
}

func TestLoadConfigAllowsExplicitLocalDemo(t *testing.T) {
	values := validConfigEnv()
	values["SMARTDATA_RUNTIME_BASE_URL"] = "http://runtime-api:8080"
	values["SMARTDATA_RUNTIME_ALLOWED_HOSTS"] = "runtime-api"
	values["SMARTDATA_MCP_ALLOW_INSECURE_UPSTREAM"] = "true"
	values["SMARTDATA_MCP_ALLOW_DEMO_AUTH"] = "true"
	values["SMARTDATA_MCP_ALLOWED_DEMO_ACTORS"] = "order-specialist, auditor"
	cfg, err := LoadConfig(env(values))
	if err != nil {
		t.Fatalf("LoadConfig() error = %v", err)
	}
	if _, ok := cfg.AllowedDemoActors["order-specialist"]; !ok {
		t.Fatal("expected explicitly configured demo actor")
	}
}

func TestLoadConfigRejectsURLCredentialsAndPaths(t *testing.T) {
	for _, runtimeURL := range []string{
		"https://user:password@runtime.internal",
		"https://runtime.internal/api",
		"https://runtime.internal?target=other",
	} {
		t.Run(runtimeURL, func(t *testing.T) {
			values := validConfigEnv()
			values["SMARTDATA_RUNTIME_BASE_URL"] = runtimeURL
			if _, err := LoadConfig(env(values)); err == nil {
				t.Fatalf("LoadConfig() accepted unsafe URL %q", runtimeURL)
			}
		})
	}
}
