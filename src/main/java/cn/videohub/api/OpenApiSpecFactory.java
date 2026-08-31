package cn.videohub.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenApiSpecFactory {
   private OpenApiSpecFactory() {
   }

   static Map<String, Object> create(String root) {
      Map<String, Object> paths = linked(
         "/open-api/v1/health",
         path("get", operation("\u670d\u52a1\u5b58\u6d3b\u4e0e\u9274\u6743\u68c0\u6d4b", "READ", "\u65e0\u9700\u53c2\u6570", null)),
         "/open-api/v1/devices",
         path(
            "get",
            operation(
               "\u67e5\u8be2\u8131\u654f\u8bbe\u5907\u5217\u8868",
               "READ",
               "\u8fd4\u56de\u8bbe\u5907\u5728\u7ebf\u72b6\u6001\u3001\u534f\u8bae\u548c\u901a\u9053\u6458\u8981",
               null
            )
         ),
         "/open-api/v1/channels",
         path(
            "get",
            operation(
               "\u5206\u9875\u67e5\u8be2\u5168\u90e8\u89c6\u9891\u901a\u9053",
               "READ",
               "limit \u6700\u5927 100",
               List.of(
                  query("offset", "integer", "\u8d77\u59cb\u504f\u79fb", 0),
                  query("limit", "integer", "\u6bcf\u9875\u6570\u91cf", 30),
                  query("q", "string", "\u540d\u79f0\u6216\u7f16\u7801\u641c\u7d22", "")
               )
            )
         ),
         "/open-api/v1/devices/{deviceId}/channels",
         path(
            "get",
            operation(
               "\u67e5\u8be2\u6307\u5b9a\u8bbe\u5907\u7684\u901a\u9053",
               "READ",
               "deviceId \u4e3a\u8bbe\u5907\u7f16\u7801",
               List.of(pathParameter("deviceId", "\u8bbe\u5907\u7f16\u7801"))
            )
         ),
         "/open-api/v1/platform",
         path("get", operation("\u67e5\u8be2\u5e73\u53f0\u3001SIP \u4e0e\u5a92\u4f53\u72b6\u6001", "READ", "\u7528\u4e8e\u63a5\u5165\u8bca\u65ad", null)),
         "/open-api/v1/snapshots/status",
         path(
            "get",
            operation(
               "\u67e5\u8be2\u62bd\u5e27\u961f\u5217\u72b6\u6001",
               "READ",
               "\u8fd4\u56de\u6392\u961f\u6570\u3001\u7f13\u5b58\u547d\u4e2d\u548c\u76f4\u64ad\u590d\u7528\u6b21\u6570",
               null
            )
         ),
         "/open-api/v1/devices/{deviceId}/snapshot",
         path(
            "get",
            binaryOperation(
               "\u6392\u961f\u83b7\u53d6\u8bbe\u5907\u5feb\u7167",
               "PLAYBACK",
               List.of(
                  pathParameter("deviceId", "\u8bbe\u5907\u7f16\u7801"),
                  query("channelId", "string", "\u89c6\u9891\u901a\u9053\u7f16\u7801\uff1bGB28181 \u5efa\u8bae\u5fc5\u586b", "34020000001320000001")
               )
            )
         ),
         "/open-api/v1/devices/{deviceId}/play",
         path(
            "post",
            bodyOperation("\u53d1\u8d77\u53d7\u63a7\u70b9\u64ad", "PLAYBACK", "PlayRequest", List.of(pathParameter("deviceId", "\u8bbe\u5907\u7f16\u7801")))
         ),
         "/open-api/v1/devices/{deviceId}/play/{playbackId}/heartbeat",
         path(
            "post",
            operation(
               "\u7ef4\u6301\u70b9\u64ad\u4f1a\u8bdd",
               "PLAYBACK",
               "\u5efa\u8bae\u6bcf 10 \u79d2\u8c03\u7528\u4e00\u6b21",
               List.of(pathParameter("deviceId", "\u8bbe\u5907\u7f16\u7801"), pathParameter("playbackId", "\u70b9\u64ad\u4f1a\u8bdd ID"))
            )
         ),
         "/open-api/v1/devices/{deviceId}/play/{playbackId}/stop",
         path(
            "post",
            operation(
               "\u4e3b\u52a8\u505c\u6b62\u70b9\u64ad",
               "PLAYBACK",
               "\u91ca\u653e\u6444\u50cf\u673a\u548c\u5a92\u4f53\u8d44\u6e90",
               List.of(pathParameter("deviceId", "\u8bbe\u5907\u7f16\u7801"), pathParameter("playbackId", "\u70b9\u64ad\u4f1a\u8bdd ID"))
            )
         ),
         "/open-api/v1/devices/{deviceId}/ptz",
         path(
            "post",
            bodyOperation(
               "\u53d1\u9001 GB28181 \u4e91\u53f0\u6307\u4ee4", "CONTROL", "PtzRequest", List.of(pathParameter("deviceId", "\u8bbe\u5907\u7f16\u7801"))
            )
         ),
         "/api/playbacks/{playbackId}/media/{file}",
         path("get", mediaOperation())
      );
      return linked(
         "openapi",
         "3.1.0",
         "info",
         linked(
            "title",
            "Mingqian Video Open API",
            "version",
            "1.0.0",
            "description",
            "\u9762\u5411\u7b2c\u4e09\u65b9\u670d\u52a1\u5668\u96c6\u6210\u7684\u8bbe\u5907\u67e5\u8be2\u3001\u62bd\u5e27\u3001\u89c6\u9891\u70b9\u64ad\u3001\u8d44\u6e90\u91ca\u653e\u548c GB28181 \u4e91\u53f0\u63a7\u5236 API\u3002",
            "termsOfService",
            root + "/developers.html",
            "contact",
            linked("name", "\u660e\u8c26\u7269\u8054\u7f51\u5e73\u53f0")
         ),
         "servers",
         List.of(linked("url", root, "description", "\u5f53\u524d\u670d\u52a1")),
         "security",
         List.of(linked("BearerAuth", List.of())),
         "tags",
         List.of(
            linked("name", "Read", "description", "\u8bbe\u5907\u3001\u901a\u9053\u4e0e\u5e73\u53f0\u67e5\u8be2"),
            linked("name", "Playback", "description", "\u70b9\u64ad\u751f\u547d\u5468\u671f\u4e0e\u5a92\u4f53\u8bbf\u95ee"),
            linked("name", "Control", "description", "GB28181 PTZ \u63a7\u5236")
         ),
         "paths",
         paths,
         "components",
         components(),
         "x-rate-limit",
         linked("policy", "per-key-per-minute", "default", 120, "retryHeader", "Retry-After"),
         "x-playback",
         linked(
            "heartbeatIntervalSeconds",
            10,
            "mediaHeaders",
            List.of("Authorization: Bearer <SAME_API_KEY>", "X-Playback-Token: <playbackToken>"),
            "note",
            "HLS \u6e05\u5355\u548c\u6bcf\u4e2a\u5206\u7247\u8bf7\u6c42\u90fd\u5fc5\u987b\u643a\u5e26\u53cc\u8bf7\u6c42\u5934"
         )
      );
   }

   private static Map<String, Object> components() {
      Map<String, Object> schemas = linked(
         "PlayRequest",
         linked(
            "type",
            "object",
            "properties",
            linked(
               "channelId",
               linked(
                  "type",
                  "string",
                  "description",
                  "\u901a\u9053\u7f16\u7801\uff1b\u5355\u901a\u9053\u8bbe\u5907\u53ef\u7701\u7565",
                  "example",
                  "34020000001320000001"
               ),
               "streamType",
               linked("type", "string", "enum", List.of("MAIN", "SUB", "THIRD"), "default", "SUB")
            )
         ),
         "PtzRequest",
         linked(
            "type",
            "object",
            "required",
            List.of("channelId", "action"),
            "properties",
            linked(
               "channelId",
               linked("type", "string", "example", "34020000001320000001"),
               "action",
               linked("type", "string", "enum", List.of("UP", "DOWN", "LEFT", "RIGHT", "ZOOM_IN", "ZOOM_OUT", "STOP")),
               "speed",
               linked("type", "integer", "minimum", 0, "maximum", 255, "default", 64)
            )
         ),
         "Error",
         linked(
            "type",
            "object",
            "required",
            List.of("ok", "message"),
            "properties",
            linked("ok", linked("type", "boolean", "const", false), "message", linked("type", "string"), "fields", linked("type", "object"))
         )
      );
      return linked(
         "securitySchemes",
         linked("BearerAuth", linked("type", "http", "scheme", "bearer", "bearerFormat", "vhk_ API Key", "description", "Authorization: Bearer vhk_...")),
         "schemas",
         schemas,
         "responses",
         linked(
            "Unauthorized",
            response("401", "API Key \u65e0\u6548\u6216\u5df2\u64a4\u9500"),
            "Forbidden",
            response("403", "API Key \u6743\u9650\u4e0d\u8db3"),
            "RateLimited",
            response("429", "\u8d85\u8fc7\u6bcf Key \u6bcf\u5206\u949f\u9650\u6d41")
         )
      );
   }

   private static Map<String, Object> operation(String summary, String scope, String description, List<Map<String, Object>> parameters) {
      String tag = "READ".equals(scope) ? "Read" : ("PLAYBACK".equals(scope) ? "Playback" : "Control");
      Map<String, Object> operation = linked(
         "summary",
         summary,
         "description",
         description,
         "tags",
         List.of(tag),
         "x-required-scope",
         scope,
         "responses",
         commonResponses(linked("description", "\u8bf7\u6c42\u6210\u529f", "content", linked("application/json", linked("schema", linked("type", "object")))))
      );
      if (parameters != null && !parameters.isEmpty()) {
         operation.put("parameters", parameters);
      }

      return operation;
   }

   private static Map<String, Object> bodyOperation(String summary, String scope, String schema, List<Map<String, Object>> parameters) {
      Map<String, Object> operation = operation(summary, scope, "\u8bf7\u6c42\u4f53\u4f7f\u7528 application/json\u3002", parameters);
      operation.put(
         "requestBody", linked("required", true, "content", linked("application/json", linked("schema", linked("$ref", "#/components/schemas/" + schema))))
      );
      return operation;
   }

   private static Map<String, Object> binaryOperation(String summary, String scope, List<Map<String, Object>> parameters) {
      Map<String, Object> operation = operation(
         summary,
         scope,
         "\u8fd4\u56de JPEG \u56fe\u7247\u3002\u4efb\u52a1\u4e32\u884c\u6267\u884c\u5e76\u4f18\u5148\u590d\u7528\u540c\u901a\u9053\u76f4\u64ad\uff1b\u54cd\u5e94\u5934\u5305\u542b X-Snapshot-Source\u3001X-Snapshot-Cache \u548c X-Snapshot-Queue-Wait-Ms\u3002\u961f\u5217\u6216\u5185\u5b58\u7e41\u5fd9\u65f6\u8fd4\u56de 503\u3002",
         parameters
      );
      operation.put(
         "responses",
         commonResponses(
            linked("description", "JPEG \u5feb\u7167", "content", linked("image/jpeg", linked("schema", linked("type", "string", "format", "binary"))))
         )
      );
      return operation;
   }

   private static Map<String, Object> mediaOperation() {
      Map<String, Object> operation = operation(
         "\u8bfb\u53d6 HLS \u6e05\u5355\u6216\u5a92\u4f53\u5206\u7247",
         "PLAYBACK",
         "\u9664 Bearer API Key \u5916\uff0c\u8fd8\u5fc5\u987b\u643a\u5e26\u70b9\u64ad\u54cd\u5e94\u4e2d\u7684 X-Playback-Token\u3002",
         List.of(
            pathParameter("playbackId", "\u70b9\u64ad\u4f1a\u8bdd ID"),
            pathParameter("file", "\u5a92\u4f53\u6587\u4ef6\u76f8\u5bf9\u8def\u5f84"),
            linked("name", "X-Playback-Token", "in", "header", "required", true, "schema", linked("type", "string"))
         )
      );
      operation.put("tags", List.of("Playback"));
      return operation;
   }

   private static Map<String, Object> commonResponses(Map<String, Object> success) {
      return linked(
         "200",
         success,
         "400",
         response("400", "\u53c2\u6570\u6216\u8bbe\u5907\u72b6\u6001\u9519\u8bef"),
         "401",
         linked("$ref", "#/components/responses/Unauthorized"),
         "403",
         linked("$ref", "#/components/responses/Forbidden"),
         "429",
         linked("$ref", "#/components/responses/RateLimited")
      );
   }

   private static Map<String, Object> response(String code, String description) {
      return linked(
         "description",
         description,
         "content",
         linked(
            "application/json",
            linked("schema", linked("$ref", "#/components/schemas/Error"), "example", linked("ok", false, "message", code + " " + description))
         )
      );
   }

   private static Map<String, Object> path(String method, Map<String, Object> operation) {
      return linked(method, operation);
   }

   private static Map<String, Object> pathParameter(String name, String description) {
      return linked("name", name, "in", "path", "required", true, "description", description, "schema", linked("type", "string"));
   }

   private static Map<String, Object> query(String name, String type, String description, Object example) {
      return linked("name", name, "in", "query", "required", false, "description", description, "schema", linked("type", type), "example", example);
   }

   private static Map<String, Object> linked(Object... pairs) {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();

      for (int index = 0; index < pairs.length; index += 2) {
         result.put((String)pairs[index], pairs[index + 1]);
      }

      return result;
   }
}
