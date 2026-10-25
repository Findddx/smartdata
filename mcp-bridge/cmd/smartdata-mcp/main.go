package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/Findddx/smartdata/mcp-bridge/internal/bridge"
)

var version = "dev"

func main() {
	cfg, err := bridge.LoadConfig(os.LookupEnv)
	if err != nil {
		slog.Error("invalid configuration", "error", err)
		os.Exit(2)
	}
	logger := newLogger(cfg.LogLevel)
	runtimeClient := bridge.NewHTTPRuntimeClient(cfg)
	mcpServer := hardenedHTTPServer(cfg.ListenAddr, bridge.NewMCPHandler(cfg, runtimeClient, logger, version))
	healthServer := hardenedHTTPServer(cfg.HealthListenAddr, bridge.NewHealthHandler(runtimeClient))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	errCh := make(chan error, 2)
	go serve(logger, "mcp", mcpServer, errCh)
	go serve(logger, "health", healthServer, errCh)
	logger.Info("MCP bridge started", "listen", cfg.ListenAddr, "health_listen", cfg.HealthListenAddr, "runtime_host", cfg.RuntimeBaseURL.Hostname(), "version", version)

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	case err := <-errCh:
		logger.Error("HTTP server stopped unexpectedly", "error", err)
		stop()
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	var wait sync.WaitGroup
	for _, server := range []*http.Server{mcpServer, healthServer} {
		wait.Add(1)
		go func(server *http.Server) {
			defer wait.Done()
			if err := server.Shutdown(shutdownCtx); err != nil {
				logger.Error("graceful shutdown failed", "error", err)
			}
		}(server)
	}
	wait.Wait()
	logger.Info("MCP bridge stopped")
}

func hardenedHTTPServer(address string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              address,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}
}

func serve(logger *slog.Logger, name string, server *http.Server, errCh chan<- error) {
	err := server.ListenAndServe()
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		errCh <- errors.New(name + " server: " + err.Error())
	}
}

func newLogger(level string) *slog.Logger {
	var slogLevel slog.Level
	switch level {
	case "debug":
		slogLevel = slog.LevelDebug
	case "warn":
		slogLevel = slog.LevelWarn
	case "error":
		slogLevel = slog.LevelError
	default:
		slogLevel = slog.LevelInfo
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slogLevel}))
}
