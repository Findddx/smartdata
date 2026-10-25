package bridge

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	defaultListenAddr       = "127.0.0.1:8082"
	defaultHealthListenAddr = "127.0.0.1:8083"
	defaultMaxRequestBytes  = int64(256 << 10)
	defaultMaxResponseBytes = int64(1 << 20)
	defaultMaxConcurrent    = 64
	defaultUpstreamTimeout  = 10 * time.Second
	defaultShutdownTimeout  = 10 * time.Second
)

// LookupEnv matches os.LookupEnv and makes configuration deterministic in tests.
type LookupEnv func(string) (string, bool)

type Config struct {
	ListenAddr            string
	HealthListenAddr      string
	RuntimeBaseURL        *url.URL
	RuntimeAllowedHosts   map[string]struct{}
	AllowedOrigins        map[string]struct{}
	AllowDemoAuth         bool
	AllowedDemoActors     map[string]struct{}
	AllowInsecureUpstream bool
	MaxRequestBytes       int64
	MaxResponseBytes      int64
	MaxConcurrentRequests int
	UpstreamTimeout       time.Duration
	ShutdownTimeout       time.Duration
	LogLevel              string
}

func LoadConfig(lookup LookupEnv) (Config, error) {
	cfg := Config{
		ListenAddr:            envOr(lookup, "SMARTDATA_MCP_LISTEN_ADDR", defaultListenAddr),
		HealthListenAddr:      envOr(lookup, "SMARTDATA_MCP_HEALTH_LISTEN_ADDR", defaultHealthListenAddr),
		RuntimeAllowedHosts:   csvSet(envOr(lookup, "SMARTDATA_RUNTIME_ALLOWED_HOSTS", "")),
		AllowedOrigins:        csvSet(envOr(lookup, "SMARTDATA_MCP_ALLOWED_ORIGINS", "")),
		AllowedDemoActors:     csvSet(envOr(lookup, "SMARTDATA_MCP_ALLOWED_DEMO_ACTORS", "")),
		MaxRequestBytes:       defaultMaxRequestBytes,
		MaxResponseBytes:      defaultMaxResponseBytes,
		MaxConcurrentRequests: defaultMaxConcurrent,
		UpstreamTimeout:       defaultUpstreamTimeout,
		ShutdownTimeout:       defaultShutdownTimeout,
		LogLevel:              strings.ToLower(envOr(lookup, "SMARTDATA_LOG_LEVEL", "info")),
	}

	var err error
	if cfg.AllowDemoAuth, err = boolEnv(lookup, "SMARTDATA_MCP_ALLOW_DEMO_AUTH", false); err != nil {
		return Config{}, err
	}
	if cfg.AllowInsecureUpstream, err = boolEnv(lookup, "SMARTDATA_MCP_ALLOW_INSECURE_UPSTREAM", false); err != nil {
		return Config{}, err
	}
	if cfg.MaxRequestBytes, err = int64Env(lookup, "SMARTDATA_MCP_MAX_REQUEST_BYTES", defaultMaxRequestBytes, 1024, 4<<20); err != nil {
		return Config{}, err
	}
	if cfg.MaxResponseBytes, err = int64Env(lookup, "SMARTDATA_MCP_MAX_RESPONSE_BYTES", defaultMaxResponseBytes, 1024, 8<<20); err != nil {
		return Config{}, err
	}
	maxConcurrent, err := int64Env(lookup, "SMARTDATA_MCP_MAX_CONCURRENT_REQUESTS", defaultMaxConcurrent, 1, 4096)
	if err != nil {
		return Config{}, err
	}
	cfg.MaxConcurrentRequests = int(maxConcurrent)
	if cfg.UpstreamTimeout, err = durationEnv(lookup, "SMARTDATA_MCP_UPSTREAM_TIMEOUT", defaultUpstreamTimeout, time.Second, 60*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.ShutdownTimeout, err = durationEnv(lookup, "SMARTDATA_MCP_SHUTDOWN_TIMEOUT", defaultShutdownTimeout, time.Second, 60*time.Second); err != nil {
		return Config{}, err
	}

	rawBaseURL := strings.TrimSpace(envOr(lookup, "SMARTDATA_RUNTIME_BASE_URL", ""))
	if rawBaseURL == "" {
		return Config{}, errors.New("SMARTDATA_RUNTIME_BASE_URL is required")
	}
	cfg.RuntimeBaseURL, err = validateRuntimeURL(rawBaseURL, cfg.AllowInsecureUpstream)
	if err != nil {
		return Config{}, err
	}
	if len(cfg.RuntimeAllowedHosts) == 0 {
		return Config{}, errors.New("SMARTDATA_RUNTIME_ALLOWED_HOSTS must explicitly allow the configured runtime host")
	}
	if _, ok := cfg.RuntimeAllowedHosts[strings.ToLower(cfg.RuntimeBaseURL.Hostname())]; !ok {
		return Config{}, fmt.Errorf("configured runtime host %q is not in SMARTDATA_RUNTIME_ALLOWED_HOSTS", cfg.RuntimeBaseURL.Hostname())
	}
	if cfg.ListenAddr == cfg.HealthListenAddr {
		return Config{}, errors.New("MCP and health listeners must use different addresses")
	}
	if err := validateListenAddr("SMARTDATA_MCP_LISTEN_ADDR", cfg.ListenAddr); err != nil {
		return Config{}, err
	}
	if err := validateListenAddr("SMARTDATA_MCP_HEALTH_LISTEN_ADDR", cfg.HealthListenAddr); err != nil {
		return Config{}, err
	}
	if cfg.AllowDemoAuth && len(cfg.AllowedDemoActors) == 0 {
		return Config{}, errors.New("demo authentication requires SMARTDATA_MCP_ALLOWED_DEMO_ACTORS")
	}
	if !cfg.AllowDemoAuth && len(cfg.AllowedDemoActors) > 0 {
		return Config{}, errors.New("demo actors are configured while demo authentication is disabled")
	}
	for origin := range cfg.AllowedOrigins {
		if err := validateOrigin(origin); err != nil {
			return Config{}, fmt.Errorf("invalid allowed origin %q: %w", origin, err)
		}
	}
	if cfg.LogLevel != "debug" && cfg.LogLevel != "info" && cfg.LogLevel != "warn" && cfg.LogLevel != "error" {
		return Config{}, errors.New("SMARTDATA_LOG_LEVEL must be debug, info, warn, or error")
	}
	return cfg, nil
}

func validateRuntimeURL(raw string, allowInsecure bool) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, fmt.Errorf("invalid SMARTDATA_RUNTIME_BASE_URL: %w", err)
	}
	if u.Scheme != "https" && !(u.Scheme == "http" && allowInsecure) {
		return nil, errors.New("runtime URL must use https; http is allowed only with SMARTDATA_MCP_ALLOW_INSECURE_UPSTREAM=true")
	}
	if u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("runtime URL must contain only scheme and authority")
	}
	if u.Path != "" && u.Path != "/" {
		return nil, errors.New("runtime URL must not contain a path")
	}
	u.Path = ""
	u.RawPath = ""
	return u, nil
}

func validateOrigin(raw string) error {
	u, err := url.Parse(raw)
	if err != nil {
		return err
	}
	if (u.Scheme != "https" && u.Scheme != "http") || u.Hostname() == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return errors.New("origin must be an http(s) scheme and authority without path, query, user info, or fragment")
	}
	return nil
}

func validateListenAddr(name, value string) error {
	host, port, err := net.SplitHostPort(value)
	if err != nil || host == "" || port == "" {
		return fmt.Errorf("%s must be an explicit host:port address", name)
	}
	parsedPort, err := strconv.ParseUint(port, 10, 16)
	if err != nil || parsedPort == 0 {
		return fmt.Errorf("%s has an invalid port", name)
	}
	return nil
}

func envOr(lookup LookupEnv, key, fallback string) string {
	if value, ok := lookup(key); ok {
		return strings.TrimSpace(value)
	}
	return fallback
}

func csvSet(raw string) map[string]struct{} {
	values := make(map[string]struct{})
	for _, value := range strings.Split(raw, ",") {
		value = strings.ToLower(strings.TrimSpace(value))
		if value != "" {
			values[value] = struct{}{}
		}
	}
	return values
}

func boolEnv(lookup LookupEnv, key string, fallback bool) (bool, error) {
	value, ok := lookup(key)
	if !ok || strings.TrimSpace(value) == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseBool(strings.TrimSpace(value))
	if err != nil {
		return false, fmt.Errorf("%s must be a boolean", key)
	}
	return parsed, nil
}

func int64Env(lookup LookupEnv, key string, fallback, minimum, maximum int64) (int64, error) {
	value, ok := lookup(key)
	if !ok || strings.TrimSpace(value) == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil || parsed < minimum || parsed > maximum {
		return 0, fmt.Errorf("%s must be between %d and %d", key, minimum, maximum)
	}
	return parsed, nil
}

func durationEnv(lookup LookupEnv, key string, fallback, minimum, maximum time.Duration) (time.Duration, error) {
	value, ok := lookup(key)
	if !ok || strings.TrimSpace(value) == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(strings.TrimSpace(value))
	if err != nil || parsed < minimum || parsed > maximum {
		return 0, fmt.Errorf("%s must be between %s and %s", key, minimum, maximum)
	}
	return parsed, nil
}
