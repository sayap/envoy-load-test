package main

import (
	"context"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/akamensky/argparse"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/encoding/protojson"

	"sample/pb"
)

type server struct {
	pb.UnimplementedTimeServer

	format   string
	loc      *time.Location
	hostname string

	grpcServer *grpc.Server
	httpServer http.Handler
}

func (s *server) init() error {
	s.grpcServer = grpc.NewServer()
	pb.RegisterTimeServer(s.grpcServer, s)
	grpc_health_v1.RegisterHealthServer(s.grpcServer, s)

	mux := http.NewServeMux()
	mux.Handle("/local", http.HandlerFunc(s.localHandler))
	mux.Handle("/health", http.HandlerFunc(s.healthHandler))
	s.httpServer = mux

	return nil
}

func (s *server) LocalTime(ctx context.Context, in *pb.LocalTimeRequest) (*pb.LocalTimeResponse, error) {
	return &pb.LocalTimeResponse{
		LocalTime: formattedTime(s.format, s.loc),
	}, nil
}

func (s *server) Check(ctx context.Context, in *grpc_health_v1.HealthCheckRequest) (*grpc_health_v1.HealthCheckResponse, error) {
	return &grpc_health_v1.HealthCheckResponse{Status: grpc_health_v1.HealthCheckResponse_SERVING}, nil
}

func (s *server) Watch(in *grpc_health_v1.HealthCheckRequest, _ grpc_health_v1.Health_WatchServer) error {
	return status.Error(codes.Unimplemented, "unimplemented")
}

func (s *server) localHandler(w http.ResponseWriter, r *http.Request) {
	jsonString := protojson.Format(&pb.LocalTimeResponse{
		LocalTime: formattedTime(s.format, s.loc),
	})
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(jsonString + "\n"))
}

func (s *server) healthHandler(w http.ResponseWriter, r *http.Request) {
	return
}

func hostname() string {
	hostname, err := os.Hostname()
	if err != nil {
		return "unknown"
	}
	return hostname
}

func formattedTime(format string, loc *time.Location) string {
	return time.Now().In(loc).Format(format)
}

func main() {
	parser := argparse.NewParser("time", "time server")
	parser.SetHelp("", "help")
	listenAddr := parser.String("l", "listen-addr", &argparse.Options{Required: true, Help: "Address to listen on"})
	timeZone := parser.String("t", "time-zone", &argparse.Options{Required: true, Help: "Time zone of the server"})
	mode := parser.Selector("m", "mode", []string{"grpc", "hybrid"}, &argparse.Options{
		Default: "hybrid", Help: "grpc: listen for grpc traffic only (with grpc-go server). " +
			"hybrid: listen for both grpc and http traffic (with built-in go server)"})
	err := parser.Parse(os.Args)
	if err != nil {
		log.Fatalf(parser.Usage(err))
	}

	loc, err := time.LoadLocation(*timeZone)
	if err != nil {
		log.Fatalf("invalid time zone: %s, err: %v", *timeZone, err)
	}

	s := &server{
		format:   time.RFC3339,
		loc:      loc,
		hostname: hostname(),
	}
	err = s.init()
	if err != nil {
		log.Fatalf("failed to init server: %v", err)
	}

	if *mode == "hybrid" {
		handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.ProtoMajor == 2 && strings.HasPrefix(r.Header.Get("Content-Type"), "application/grpc") {
				s.grpcServer.ServeHTTP(w, r)
			} else {
				s.httpServer.ServeHTTP(w, r)
			}
		})

		server := http.Server{
			Addr:    *listenAddr,
			Handler: h2c.NewHandler(handler, &http2.Server{}),
		}

		log.Print("Listening...")
		server.ListenAndServe()
	} else {
		lis, err := net.Listen("tcp", *listenAddr)
		if err != nil {
			log.Fatalf("failed to listen: %v", err)
		}
		s.grpcServer.Serve(lis)
	}
}
