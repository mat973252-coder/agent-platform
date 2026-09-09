package io.github.mat973252.agentplatform.api;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/runs")
class RunObservationController {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final RunObservationService observations;
  private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore slots = new Semaphore(32);

  RunObservationController(RunObservationService observations) { this.observations = observations; }

  @ModelAttribute
  void preventCaching(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

  @GetMapping
  RunObservation.Listing list(@RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "") String pageToken) { return observations.list(limit, pageToken); }

  @GetMapping("/{runId}/history")
  RunObservation.Detail detail(@PathVariable String runId, @RequestParam(defaultValue = "true") boolean refresh) {
    return observations.detail(runId, refresh);
  }

  @GetMapping("/{runId}/events")
  RunObservation.EventPage events(@PathVariable String runId, @RequestParam(defaultValue = "") String after,
      @RequestParam(defaultValue = "100") int limit) { return observations.events(runId, after, limit); }

  @GetMapping(value = "/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream(@PathVariable String runId, @RequestParam(defaultValue = "") String after,
      @RequestHeader(value = "Last-Event-ID", defaultValue = "") String lastEventId, HttpServletResponse response) {
    if (!after.isEmpty() && !lastEventId.isEmpty() && !after.equals(lastEventId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Conflicting event cursors");
    }
    if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many event streams");
    try {
      var first = observations.events(runId, lastEventId.isEmpty() ? after : lastEventId, 500);
      var emitter = new SseEmitter(30_000L);
      var stopped = new AtomicBoolean();
      emitter.onCompletion(() -> stopped.set(true));
      emitter.onTimeout(() -> stopped.set(true));
      emitter.onError(error -> stopped.set(true));
      response.setHeader("X-Accel-Buffering", "no");
      streams.submit(() -> emit(runId, first, emitter, stopped));
      return emitter;
    } catch (RuntimeException failure) {
      slots.release();
      throw failure;
    }
  }

  private void emit(String runId, RunObservation.EventPage page, SseEmitter emitter, AtomicBoolean stopped) {
    long deadline = System.nanoTime() + 25_000_000_000L;
    try {
      while (!stopped.get() && System.nanoTime() < deadline) {
        for (var event : page.events()) {
          emitter.send(SseEmitter.event().id(event.id()).name("history").data(JSON.writeValueAsString(event)));
        }
        if (page.closed() && !page.hasMore()) break;
        if (!page.hasMore()) {
          emitter.send(SseEmitter.event().comment("heartbeat"));
          Thread.sleep(1000);
        }
        if (!stopped.get()) page = observations.events(runId, page.nextCursor(), 500);
      }
      emitter.complete();
    } catch (IOException | IllegalStateException disconnected) {
      emitter.complete();
    } catch (InterruptedException shutdown) {
      Thread.currentThread().interrupt();
      emitter.complete();
    } catch (RuntimeException unavailable) {
      // No error payload or new cursor: reconnect from the last successfully delivered event.
      emitter.complete();
    } finally {
      slots.release();
    }
  }

  @PreDestroy
  void stopStreams() { streams.shutdownNow(); }
}
