package org.veupathdb.service.demo.generated.resources;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import org.veupathdb.service.demo.generated.model.ServerErrorResponse;
import org.veupathdb.service.demo.generated.support.ResponseDelegate;

@Path("/metrics")
public interface Metrics {
  @GET
  @Produces({
      "application/json",
      "text/plain"
  })
  GetMetricsResponse getMetrics();

  class GetMetricsResponse extends ResponseDelegate {
    private GetMetricsResponse(Response response, Object entity) {
      super(response, entity);
    }

    private GetMetricsResponse(Response response) {
      super(response);
    }

    public static GetMetricsResponse respond200WithTextPlain(Object entity) {
      Response.ResponseBuilder responseBuilder = Response.status(200).header("Content-Type", "text/plain");
      responseBuilder.entity(entity);
      return new GetMetricsResponse(responseBuilder.build(), entity);
    }

    public static GetMetricsResponse respond500WithApplicationJson(ServerErrorResponse entity) {
      Response.ResponseBuilder responseBuilder = Response.status(500).header("Content-Type", "application/json");
      responseBuilder.entity(entity);
      return new GetMetricsResponse(responseBuilder.build(), entity);
    }
  }
}