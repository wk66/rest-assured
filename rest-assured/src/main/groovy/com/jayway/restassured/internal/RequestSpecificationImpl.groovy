/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jayway.restassured.internal

import com.jayway.restassured.authentication.AuthenticationScheme
import com.jayway.restassured.authentication.NoAuthScheme
import com.jayway.restassured.config.HttpClientConfig
import com.jayway.restassured.config.RedirectConfig
import com.jayway.restassured.config.RestAssuredConfig
import com.jayway.restassured.filter.Filter
import com.jayway.restassured.filter.log.ErrorLoggingFilter
import com.jayway.restassured.filter.log.ResponseLoggingFilter
import com.jayway.restassured.internal.encoderregistry.RestAssuredEncoderRegistry
import com.jayway.restassured.internal.filter.FilterContextImpl
import com.jayway.restassured.internal.filter.RootFilter
import com.jayway.restassured.internal.mapping.ObjectMapping
import com.jayway.restassured.mapper.ObjectMapper
import groovyx.net.http.HTTPBuilder.RequestConfigDelegate
import java.util.Map.Entry
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.apache.commons.lang.StringUtils
import org.apache.commons.lang.Validate
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.entity.HttpEntityWrapper

import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.message.BasicHeader
import static com.jayway.restassured.assertion.AssertParameter.notNull
import com.jayway.restassured.response.*
import com.jayway.restassured.specification.*
import groovyx.net.http.*
import static groovyx.net.http.ContentType.*
import static groovyx.net.http.Method.*
import static java.util.Arrays.asList
import static org.apache.commons.lang.StringUtils.substringAfter
import static org.apache.http.client.params.ClientPNames.*
import static org.apache.http.protocol.HTTP.CONTENT_TYPE
import static org.apache.http.entity.mime.HttpMultipartMode.BROWSER_COMPATIBLE

import org.apache.http.impl.conn.ProxySelectorRoutePlanner

class RequestSpecificationImpl implements FilterableRequestSpecification {
  private static final int DEFAULT_HTTPS_PORT = 443
  private static final int DEFAULT_HTTP_PORT = 80
  private static final int DEFAULT_HTTP_TEST_PORT = 8080
  private static final String MULTIPART_FORM_DATA = "multipart/form-data"
  private static final String SLASH = "/"

  private String baseUri
  private String path  = ""
  private String basePath
  private AuthenticationScheme defaultAuthScheme
  private int port
  private Map<String, Object> requestParameters = [:]
  private Map<String, Object> queryParameters = [:]
  private Map<String, Object> formParameters = [:]
  private Map<String, Object> pathParameters = [:]
  private Map<String, Object> httpClientParams = [:]
  def AuthenticationScheme authenticationScheme = new NoAuthScheme()
  private FilterableResponseSpecification responseSpecification;
  private Object contentType;
  private Headers requestHeaders = new Headers([])
  private Cookies cookies = new Cookies([])
  private Object requestBody;
  private List<Filter> filters = [];
  private KeystoreSpec keyStoreSpec
  private boolean urlEncodingEnabled
  private RestAssuredConfig restAssuredConfig;
  private List<MultiPart> multiParts = [];

  public RequestSpecificationImpl (String baseURI, int requestPort, String basePath, AuthenticationScheme defaultAuthScheme,
                                   List<Filter> filters, KeystoreSpec keyStoreSpec, defaultRequestContentType, RequestSpecification defaultSpec,
                                   boolean urlEncode, RestAssuredConfig restAssuredConfig) {
    notNull(baseURI, "baseURI");
    notNull(basePath, "basePath");
    notNull(defaultAuthScheme, "defaultAuthScheme");
    notNull(filters, "Filters")
    notNull(keyStoreSpec, "Keystore specification")
    notNull(urlEncode, "URL Encode query params option")
    this.baseUri = baseURI
    this.basePath = basePath
    this.defaultAuthScheme = defaultAuthScheme
    this.filters.addAll(filters)
    this.contentType = defaultRequestContentType
    this.keyStoreSpec = keyStoreSpec
    this.urlEncodingEnabled = urlEncode
    this.restAssuredConfig = restAssuredConfig
    port(requestPort)
    if(defaultSpec != null) {
      spec(defaultSpec)
    }
  }

  def RequestSpecification when() {
    return this;
  }

  def RequestSpecification given() {
    return this;
  }

  def RequestSpecification that() {
    return this;
  }

  def ResponseSpecification response() {
    return responseSpecification;
  }

  def Response get(String path, Object...pathParams) {
    applyPathParamsAndSendRequest(GET, path, pathParams)
  }

  def Response post(String path, Object...pathParams) {
    applyPathParamsAndSendRequest(POST, path, pathParams)
  }

  def Response put(String path, Object...pathParams) {
    applyPathParamsAndSendRequest(PUT, path, pathParams)
  }

  def Response delete(String path, Object...pathParams) {
    applyPathParamsAndSendRequest(DELETE, path, pathParams)
  }

  def Response head(String path, Object...pathParams) {
    applyPathParamsAndSendRequest(HEAD, path, pathParams)
  }

  def Response get(String path, Map pathParams) {
    pathParameters(pathParams)
    applyPathParamsAndSendRequest(GET, path)
  }

  def Response post(String path, Map pathParams) {
    pathParameters(pathParams)
    applyPathParamsAndSendRequest(POST, path)
  }

  def Response put(String path, Map pathParams) {
    pathParameters(pathParams)
    applyPathParamsAndSendRequest(PUT, path)
  }

  def Response delete(String path, Map pathParams) {
    pathParameters(pathParams)
    applyPathParamsAndSendRequest(DELETE, path)
  }

  def Response head(String path, Map pathParams) {
    pathParameters(pathParams)
    applyPathParamsAndSendRequest(HEAD, path)
  }

  def RequestSpecification parameters(String firstParameterName, Object firstParameterValue, Object... parameterNameValuePairs) {
    notNull firstParameterName, "firstParameterName"
    notNull firstParameterValue, "firstParameterValue"
    return parameters(MapCreator.createMapFromParams(firstParameterName, firstParameterValue, parameterNameValuePairs))
  }

  def RequestSpecification parameters(Map parametersMap) {
    notNull parametersMap, "parametersMap"
    appendParameters(parametersMap, requestParameters)
    return this
  }

  def RequestSpecification params(String firstParameterName, Object firstParameterValue, Object... parameterNameValuePairs) {
    return parameters(firstParameterName, firstParameterValue, parameterNameValuePairs)
  }

  def RequestSpecification params(Map parametersMap) {
    return parameters(parametersMap)
  }

  def RequestSpecification param(String parameterName, Object parameterValue, Object... additionalParameterValues) {
    return parameter(parameterName, parameterValue, additionalParameterValues)
  }

  def RequestSpecification parameter(String parameterName, List parameterValues) {
    notNull parameterName, "parameterName"
    notNull parameterValues, "parameterValues"
    appendListParameter(requestParameters, parameterName, parameterValues)
    return this
  }

  def RequestSpecification param(String parameterName, List parameterValues) {
    return parameter(parameterName, parameterValues)
  }

  def RequestSpecification queryParameter(String parameterName, List parameterValues) {
    notNull parameterName, "parameterName"
    notNull parameterValues, "parameterValues"
    appendListParameter(queryParameters, parameterName, parameterValues)
    return this
  }

  def RequestSpecification queryParam(String parameterName, List parameterValues) {
    return queryParameter(parameterName, parameterValues)
  }

  def RequestSpecification parameter(String parameterName, Object parameterValue, Object... additionalParameterValues) {
    notNull parameterName, "parameterName"
    notNull parameterValue, "parameterValue"
    appendStandardParameter(requestParameters, parameterName, parameterValue);
    if(additionalParameterValues != null) {
      appendListParameter(requestParameters, parameterName, asList(additionalParameterValues))
    }
    return this
  }

  def RequestSpecification queryParameters(String firstParameterName, Object firstParameterValue, Object... parameterNameValuePairs) {
    notNull firstParameterName, "firstParameterName"
    notNull firstParameterValue, "firstParameterValue"
    return queryParameters(MapCreator.createMapFromParams(firstParameterName, firstParameterValue, parameterNameValuePairs))
  }

  def RequestSpecification queryParameters(Map parametersMap) {
    notNull parametersMap, "parametersMap"
    appendParameters(parametersMap, queryParameters)
    return this
  }

  def RequestSpecification queryParameter(String parameterName, Object parameterValue, Object... additionalParameterValues) {
    notNull parameterName, "parameterName"
    notNull parameterValue, "parameterValue"
    appendStandardParameter(queryParameters, parameterName, parameterValue)
    if(additionalParameterValues != null) {
      appendListParameter(queryParameters, parameterName, asList(additionalParameterValues))
    }
    return this
  }

  def RequestSpecification queryParams(String firstParameterName, Object firstParameterValue, Object... parameterNameValuePairs) {
    return queryParameters(firstParameterName, firstParameterValue, parameterNameValuePairs);
  }

  def RequestSpecification queryParams(Map parametersMap) {
    return queryParameters(parametersMap)
  }

  def RequestSpecification queryParam(String parameterName, Object parameterValue, Object... additionalParameterValues) {
    return queryParameter(parameterName, parameterValue, additionalParameterValues)
  }

  def RequestSpecification formParameter(String parameterName, List parameterValues) {
    notNull parameterName, "parameterName"
    notNull parameterValues, "parameterValues"
    appendListParameter(formParameters, parameterName, parameterValues)
    return this
  }

  def RequestSpecification formParam(String parameterName, List parameterValues) {
    return formParameter(parameterName, parameterValues)
  }

  def RequestSpecification formParameters(String firstParameterName, Object firstParameterValue, Object... parameterNameValuePairs) {
    notNull firstParameterName, "firstParameterName"
    notNull firstParameterValue, "firstParameterValue"
    return formParameters(MapCreator.createMapFromParams(firstParameterName, firstParameterValue, parameterNameValuePairs))
  }

  def RequestSpecification formParameters(Map parametersMap) {
    notNull parametersMap, "parametersMap"
    appendParameters(parametersMap, formParameters)
    return this
  }

  def RequestSpecification formParameter(String parameterName, Object parameterValue, Object... additionalParameterValues) {
    notNull parameterName, "parameterName"
    notNull parameterValue, "parameterValue"
    appendStandardParameter(formParameters, parameterName, parameterValue)
    if(additionalParameterValues != null) {
      appendListParameter(formParameters, parameterName, asList(additionalParameterValues))
    }
    return this
  }

  def RequestSpecification formParams(String firstParameterName, Object firstParameterValue, Object... parameterNameValuePairs) {
    return formParameters(firstParameterName, firstParameterValue, parameterNameValuePairs);
  }

  def RequestSpecification formParams(Map parametersMap) {
    return formParameters(parametersMap)
  }

  def RequestSpecification formParam(String parameterName, Object parameterValue, Object... additionalParameterValues) {
    return formParameter(parameterName, parameterValue, additionalParameterValues)
  }

  def RequestSpecification urlEncodingEnabled(boolean isEnabled) {
    this.urlEncodingEnabled = isEnabled
    return this
  }

  def RequestSpecification pathParameter(String parameterName, Object parameterValue) {
    notNull parameterName, "parameterName"
    notNull parameterValue, "parameterValue"
    appendStandardParameter(pathParameters, parameterName, parameterValue)
    return this
  }

  def RequestSpecification pathParameters(String firstParameterName, Object firstParameterValue, Object... parameterNameValuePairs) {
    notNull firstParameterName, "firstParameterName"
    notNull firstParameterValue, "firstParameterValue"
    return pathParameters(MapCreator.createMapFromParams(firstParameterName, firstParameterValue, parameterNameValuePairs))
  }

  def RequestSpecification pathParameters(Map parameterNameValuePairs) {
    notNull parameterNameValuePairs, "parameterNameValuePairs"
    appendParameters(parameterNameValuePairs, pathParameters)
    return this
  }

  def RequestSpecification pathParam(String parameterName, Object parameterValue) {
    return pathParameter(parameterName, parameterValue)
  }

  def RequestSpecification pathParams(String firstParameterName, Object firstParameterValue, Object... parameterNameValuePairs) {
    return pathParameters(firstParameterName, firstParameterValue, parameterNameValuePairs)
  }

  def RequestSpecification pathParams(Map parameterNameValuePairs) {
    return pathParameters(parameterNameValuePairs)
  }

  def RequestSpecification config(RestAssuredConfig config) {
    this.restAssuredConfig = config
    this
  }

  def RequestSpecification keystore(File pathToJks, String password) {
    Validate.notNull(pathToJks, "Path to java keystore cannot be empty");
    Validate.notEmpty(password, "Password cannot be empty");
    this.keyStoreSpec = new KeystoreSpecImpl(path: pathToJks, password: password)
    this
  }

  def RequestSpecification keystore(String pathToJks, String password) {
    Validate.notEmpty(pathToJks, "Path to java keystore cannot be empty");
    Validate.notEmpty(password, "Password cannot be empty");
    this.keyStoreSpec = new KeystoreSpecImpl(path: pathToJks, password: password)
    this
  }

  def RequestSpecification filter(Filter filter) {
    notNull filter, "Filter"
    filters << filter
    return this
  }

  def RequestSpecification filters(List<Filter> filters) {
    notNull filters, "Filters"
    this.filters.addAll(filters)
    return this
  }

  def RequestSpecification filters(Filter filter, Filter...additionalFilter) {
    notNull filter, "Filter"
    this.filters.add(filter)
    additionalFilter?.each {
      this.filters.add(it)
    }
    return this
  }

  def RequestLogSpecification log() {
    return new RequestLogSpecificationImpl(requestSpecification: this)
  }

  def RequestSpecification and() {
    return this;
  }

  def RequestSpecification request() {
    return this;
  }

  def RequestSpecification with() {
    return this;
  }

  def ResponseSpecification then() {
    return responseSpecification;
  }

  def ResponseSpecification expect() {
    return responseSpecification;
  }

  def AuthenticationSpecification auth() {
    return new AuthenticationSpecificationImpl(this);
  }

  def AuthenticationSpecification authentication() {
    return auth();
  }

  def RequestSpecification port(int port) {
    if(port < 1) {
      throw new IllegalArgumentException("Port must be greater than 0")
    }
    this.port = port
    return this
  }

  def RequestSpecification body(String body) {
    notNull body, "body"
    this.requestBody = body;
    return this;
  }

  RequestSpecification content(String content) {
    notNull content, "content"
    this.requestBody = content;
    return this
  }

  def RequestSpecification body(byte[] body) {
    notNull body, "body"
    this.requestBody = body;
    return this;
  }

  def RequestSpecification content(byte[] content) {
    notNull content, "content"
    return body(content);
  }

  def RequestSpecification body(Object object) {
    notNull object, "object"
    if(!isSerializableCandidate(object)) {
      return content(object.toString());
    }

    this.requestBody = ObjectMapping.serialize(object, requestContentType, null)
    this
  }

  def RequestSpecification content(Object object) {
    return body(object)
  }

  def RequestSpecification body(Object object, ObjectMapper mapper) {
    notNull object, "object"
    notNull mapper, "Object mapper"
    this.requestBody = ObjectMapping.serialize(object, requestContentType, mapper)
    this
  }

  def RequestSpecification content(Object object, ObjectMapper mapper) {
    return body(object, mapper)
  }

  def RequestSpecification contentType(ContentType contentType) {
    notNull contentType, "contentType"
    this.contentType = contentType
    return  this
  }

  def RequestSpecification contentType(String contentType) {
    notNull contentType, "contentType"
    this.contentType = contentType
    return  this
  }

  def RequestSpecification headers(Map headers) {
    notNull headers, "headers"
    def headerList = []
    if(this.requestHeaders.exist()) {
      headerList.addAll(this.requestHeaders.list())
    }
    headers.each {
      headerList << new Header(it.key, it.value)
    }
    this.requestHeaders = new Headers(headerList)
    return this;
  }

  RequestSpecification headers(Headers headers) {
    notNull headers, "headers"
    if(headers.exist()) {
      def headerList = []
      if(this.requestHeaders.exist()) {
        headerList.addAll(this.requestHeaders.list())
      }

      headerList.addAll(headers.headers.list())
      this.requestHeaders = new Headers(headerList)
    }
    this
  }

  RequestSpecification header(String headerName, Object headerValue, Object...additionalHeaderValues) {
    notNull headerName, "Header name"
    notNull headerValue, "Header value"
    def headerList = [new Header(headerName, serializeIfNeeded(headerValue))]
    additionalHeaderValues?.each {
      headerList << new Header(headerName, serializeIfNeeded(it))
    }

    return headers(new Headers(headerList))
  }

  def RequestSpecification header(Header header) {
    notNull header, "Header"
    return headers(new Headers(asList(header)));
  }

  RequestSpecification headers(String firstHeaderName, Object firstHeaderValue, Object... headerNameValuePairs) {
    return headers(MapCreator.createMapFromParams(firstHeaderName, firstHeaderValue, headerNameValuePairs))
  }

  RequestSpecification cookies(String firstCookieName, Object firstCookieValue, Object... cookieNameValuePairs) {
    return cookies(MapCreator.createMapFromParams(firstCookieName, firstCookieValue, cookieNameValuePairs))
  }

  RequestSpecification cookies(Map cookies) {
    notNull cookies, "cookies"
    def cookieList = []
    if(this.cookies.exist()) {
      cookieList.addAll(this.cookies.list())
    }
    cookies.each {
      cookieList << new Cookie.Builder(it.key, it.value).build();
    }
    this.cookies = new Cookies(cookieList)
    return this;
  }

  def RequestSpecification cookies(Cookies cookies) {
    notNull cookies, "cookies"
    if(cookies.exist()) {
      def cookieList = []
      if(this.cookies.exist()) {
        cookieList.addAll(this.cookies.list())
      }

      cookieList.addAll(cookies.cookies.list())
      this.cookies = new Cookies(cookieList)
    }
    this
  }

  RequestSpecification cookie(String cookieName, Object value, Object...additionalValues) {
    notNull cookieName, "Cookie name"
    def cookieList = [new Cookie.Builder(cookieName, serializeIfNeeded(value)).build()]
    additionalValues?.each {
      cookieList << new Cookie.Builder(cookieName, serializeIfNeeded(it)).build()
    }

    return cookies(new Cookies(cookieList))
  }

  def RequestSpecification cookie(Cookie cookie) {
    notNull cookie, "Cookie"
    return cookies(new Cookies(asList(cookie)));
  }

  def RequestSpecification cookie(String cookieName) {
    cookie(cookieName, null)
  }

  def RedirectSpecification redirects() {
    new RedirectSpecificationImpl(this, httpClientParams)
  }

  RequestSpecification spec(RequestSpecification requestSpecificationToMerge) {
    SpecificationMerger.merge this, requestSpecificationToMerge
    return this
  }

  RequestSpecification specification(RequestSpecification requestSpecificationToMerge) {
    return spec(requestSpecificationToMerge)
  }

  def RequestSpecification multiPart(String controlName, File file) {
    multiParts << new MultiPart(name: controlName, content: file)
    this
  }

  def RequestSpecification multiPart(File file) {
    multiParts << new MultiPart(name: "file", content: file)
    this
  }

  def RequestSpecification multiPart(String name, File file, String mimeType) {
    multiParts << new MultiPart(name: name, content: file, mimeType: mimeType)
    this
  }

  def RequestSpecification multiPart(String name, String fileName, byte[] bytes) {
    multiParts << new MultiPart(name: name, content: bytes, fileName: fileName)
    this
  }

  def RequestSpecification multiPart(String name, String fileName, byte[] bytes, String mimeType) {
    multiParts << new MultiPart(name: name, content: bytes, mimeType: mimeType, fileName: fileName)
    this
  }

  def RequestSpecification multiPart(String name, String fileName, InputStream stream) {
    multiParts << new MultiPart(name: name, content: stream, fileName: fileName)
    this
  }

  def RequestSpecification multiPart(String name, String fileName, InputStream stream, String mimeType) {
    multiParts << new MultiPart(name: name, content: stream, mimeType: mimeType, fileName: fileName)
    this
  }

  def RequestSpecification multiPart(String name, String contentBody) {
    multiParts << new MultiPart(name: name, content: contentBody)
    this
  }

  def RequestSpecification multiPart(String name, String contentBody, String mimeType) {
    multiParts << new MultiPart(name: name, content: contentBody, mimeType: mimeType)
    this
  }

  def invokeFilterChain(path, method, assertionClosure) {
    filters << new RootFilter()
    def ctx = new FilterContextImpl(path, method, assertionClosure, filters);
    def response = ctx.next(this, responseSpecification)
    responseSpecification.assertionClosure.validate(response)
    return response;
  }

  private def Response sendRequest(path, method, assertionClosure) {
    path = extractRequestParamsIfNeeded(method, path);
    def isFullyQualifiedUri = isFullyQualified(path)
    def targetUri = getTargetURI(path);
    def targetPath = getTargetPath(path)
    def http = new HTTPBuilder(targetUri) {
      {
        encoderRegistry = new RestAssuredEncoderRegistry();
      }

      @Override protected Object doRequest(RequestConfigDelegate delegate) {
        // When doing POST we must add the failure handler here
        // in order to be able to return the response when doing
        // e.g. post("/ikk"); when an error occurs.
        if(delegate.getRequest() instanceof HttpPost) {
          if (assertionClosure != null ) {
            delegate.getResponse().put(
                    Status.FAILURE.toString(), { response, content ->
                      assertionClosure.call (response, content)
                    });
          }
          delegate.uri.query = queryParameters
        } else if(!urlEncodingEnabled) {
          // Overwrite the URL encoded query parameters with the original ones
          delegate.uri.query = queryParameters
        }

        return restAssuredDoRequest(this, delegate)
      }

      /**
       * We override this method because ParserRegistry.getContentType(..) called by
       * the super method throws an exception if no content-type is available in the response
       * and then HTTPBuilder for some reason uses the streaming octet parser instead of the
       * defaultParser in the ParserRegistry to parse the response. To fix this we set the
       * content-type of the defaultParser if registered to Rest Assured to the response if no
       * content-type is defined.
       */
      @Override
      protected Object parseResponse(HttpResponse resp, Object contentType) {
        def definedDefaultParser = RequestSpecificationImpl.this.responseSpecification.rpr.defaultParser
        if(definedDefaultParser != null && ContentType.ANY.toString().equals( contentType.toString() ) ) {
          try {
            ParserRegistry.getContentType(resp);
          } catch(IllegalArgumentException e) {
            // This means that no content-type is defined the response
            def entity = resp?.entity
            if(entity != null) {
              resp.setEntity(new HttpEntityWrapper(entity) {
                @Override
                org.apache.http.Header getContentType() {
                  return new BasicHeader(CONTENT_TYPE, definedDefaultParser.getContentType())
                }
              })
            }
          }
        }
        return super.parseResponse(resp, contentType)
      }
    };

    // make client aware of JRE proxy settings http://freeside.co/betamax/
    http.client.routePlanner = new ProxySelectorRoutePlanner(
        http.client.connectionManager.schemeRegistry,
        ProxySelector.default
    )
    
    applyRestAssuredConfig(http)
    registerRestAssuredEncoders(http);
    RestAssuredParserRegistry.responseSpecification = responseSpecification
    http.setParserRegistry(new RestAssuredParserRegistry())
    responseSpecification.rpr.registerParsers(http, assertionClosure.requiresTextParsing())
    setRequestHeadersToHttpBuilder(http)

    if(cookies.exist()) {
      http.getHeaders() << [Cookie : cookies.collect { it.toString() }.join("; ")]
    }

    // Allow returning a the response
    def restAssuredResponse = new RestAssuredResponseImpl()
    responseSpecification.restAssuredResponse = restAssuredResponse
    def responseContentType =  assertionClosure.getResponseContentType()

    if(authenticationScheme instanceof NoAuthScheme && !(defaultAuthScheme instanceof NoAuthScheme)) {
      // Use default auth scheme
      authenticationScheme = defaultAuthScheme
    }

    authenticationScheme.authenticate(http)

    keyStoreSpec.apply(http, isFullyQualifiedUri == true && port == DEFAULT_HTTP_TEST_PORT ? DEFAULT_HTTPS_PORT : port)

    validateMultiPartForPostOnly(method);

    if(shouldUrlEncode(method)) {
      if(hasFormParams() && requestBody != null) {
        throw new IllegalStateException("You can either send form parameters OR body content in $method, not both!");
      }
      def bodyContent = assembleBodyContent(method)
      if(method == POST) {
        http.post( path: targetPath, body: bodyContent,
                requestContentType: defineRequestContentType(method),
                contentType: responseContentType) { response, content ->
          if(assertionClosure != null) {
            assertionClosure.call (response, content)
          }
        }
      } else {
        requestBody = createBodyContent(bodyContent)
        sendHttpRequest(http, method, responseContentType, targetPath, assertionClosure)
      }
    } else {
      sendHttpRequest(http, method, responseContentType, targetPath, assertionClosure)
    }
    return restAssuredResponse
  }

  def applyRestAssuredConfig(HTTPBuilder http) {
    if(restAssuredConfig != null) {
      applyRedirectConfig(restAssuredConfig.getRedirectConfig())
      applyHttpClientConfig(restAssuredConfig.getHttpClientConfig())
    }
    if (!httpClientParams.isEmpty()) {
      def p = http.client.getParams();

      httpClientParams.each { key, value ->
        p.setParameter(key, value)
      }
    }
  }

  def applyHttpClientConfig(HttpClientConfig httpClientConfig) {
    ([:].plus(httpClientConfig.params())).each { key, value ->
      putIfAbsent(httpClientParams, key, value)
    }
  }

  def applyRedirectConfig(RedirectConfig redirectConfig) {
    putIfAbsent(httpClientParams, ALLOW_CIRCULAR_REDIRECTS, redirectConfig.allowsCircularRedirects())
    putIfAbsent(httpClientParams, HANDLE_REDIRECTS, redirectConfig.followsRedirects())
    putIfAbsent(httpClientParams, MAX_REDIRECTS, redirectConfig.maxRedirects())
    putIfAbsent(httpClientParams, REJECT_RELATIVE_REDIRECT, redirectConfig.rejectRelativeRedirects())
  }

  private def putIfAbsent(Map map, key, value) {
    if(!map.containsKey(key)) {
      map.put(key, value)
    }
  }

  def assembleBodyContent(httpMethod) {
    if(hasFormParams()) {
      httpMethod == Method.POST ? requestParameters += formParameters : formParameters
    } else if(multiParts.isEmpty()) {
      requestBody
    } else {
      new byte[0]
    }
  }

  def setRequestHeadersToHttpBuilder(HTTPBuilder http) {
    def httpHeaders = http.getHeaders();
    requestHeaders.each { header ->
      def headerName = header.getName()
      def headerValue = header.getValue()
      if (httpHeaders.containsKey(headerName)) {
        def values = [httpHeaders.get(headerName)];
        values << headerValue
        def headerVal = values.flatten()
        httpHeaders.put(headerName, headerVal)
      } else {
        httpHeaders.put(headerName, headerValue)
      }
    }
  }

  private def createBodyContent(bodyContent) {
    return bodyContent instanceof Map ? createFormParamBody(bodyContent) : bodyContent
  }

  private String getTargetPath(String path) {
    if(isFullyQualified(path)) {
      return new URL(path).getPath()
    }

    def baseUriPath = ""
    if(!(baseUri == null || baseUri == "")) {
      def uri = new URI(baseUri)
      baseUriPath = uri.getPath()
    }
    return mergeAndRemoveDoubleSlash(mergeAndRemoveDoubleSlash(baseUriPath, basePath), path)
  }

  private def validateMultiPartForPostOnly(method) {
    if(multiParts.size() > 0 && method != POST) {
      throw new IllegalArgumentException("Sorry, multi part form data is only available for "+POST);
    }
  }

  private def registerRestAssuredEncoders(HTTPBuilder http) {
    // Multipart form-data
    if(multiParts.isEmpty()) {
      return;
    }

    if(hasFormParams()) {
      convertFormParamsToMultiPartParams()
    }
    http.encoder.putAt MULTIPART_FORM_DATA, {
      MultipartEntity entity = new MultipartEntity(BROWSER_COMPATIBLE);

      multiParts.each {
        def body = it.contentBody
        def name = it.name
        entity.addPart(name, body);
      }

      return entity;
    }
  }

  private def convertFormParamsToMultiPartParams() {
    def allFormParams = requestParameters += formParameters
    allFormParams.each {
      multiPart(it.key, it.value)
    }
    requestParameters.clear()
    formParameters.clear()
  }

  private def sendHttpRequest(HTTPBuilder http, method, responseContentType, targetPath, assertionClosure) {
    def allQueryParams = requestParameters += queryParameters
    http.request(method, responseContentType) {
      uri.path = targetPath

      setRequestContentType(defineRequestContentTypeAsString(method))

      if (requestBody != null) {
        body = requestBody
      }

      uri.query = allQueryParams

      Closure closure = assertionClosure.getClosure()
      // response handler for a success response code:
      response.success = closure

      // handler for any failure status code:
      response.failure = closure
    }
  }

  private boolean hasFormParams() {
    return !(requestParameters.isEmpty() && formParameters.isEmpty())
  }

  private boolean shouldUrlEncode(method) {
    return POST.equals(method) || formParameters.size() > 0
  }

  private boolean isFullyQualified(String targetUri) {
    return targetUri.contains("://")
  }

  private String extractRequestParamsIfNeeded(Method method, String path) {
    if(path.contains("?")) {
      def indexOfQuestionMark = path.indexOf("?")
      String allParamAsString = path.substring(indexOfQuestionMark+1);
      def keyValueParams = allParamAsString.split("&");
      keyValueParams.each {
        def keyValue = StringUtils.split(it, "=", 2)
        if(keyValue.length != 2) {
          throw new IllegalArgumentException("Illegal parameters passed to REST Assured. Parameters was: $keyValueParams")
        }
        if(method == POST) {
          queryParameters.put(keyValue[0], keyValue[1])
        } else {
          param(keyValue[0], keyValue[1]);
        }
      };
      path = path.substring(0, indexOfQuestionMark);
    }
    return path;
  }

  private def defineRequestContentTypeAsString(Method method) {
    return defineRequestContentType(method).toString()
  }

  private def defineRequestContentType(Method method) {
    if (contentType == null) {
      if(multiParts.size() > 0) {
        contentType = MULTIPART_FORM_DATA
      } else if (requestBody == null) {
        contentType = shouldUrlEncode(method) ? URLENC : ANY
      } else if (requestBody instanceof byte[]) {
        if(method != POST && method != PUT) {
          throw new IllegalStateException("$method doesn't support binary request data.");
        }
        contentType = BINARY
      } else {
        contentType = TEXT
      }
    }
    contentType
  }

  private String getTargetURI(String path) {
    if(port <= 0) {
      throw new IllegalArgumentException("Port must be greater than 0")
    }
    def uri
    def pathHasScheme = isFullyQualified(path)
    if(pathHasScheme) {
      def url = new URL(path)
      uri = getTargetUriFromUrl(url)
    } else if(isFullyQualified(baseUri)) {
      def baseUriAsUrl = new URL(baseUri)
      uri = getTargetUriFromUrl(baseUriAsUrl)
    } else {
      uri = "$baseUri:$port"
    }
    return uri
  }

  private String getTargetUriFromUrl(URL url) {
    def builder = new StringBuilder();
    def protocol = url.getProtocol()
    def boolean useDefaultHttps = false
    if(port == DEFAULT_HTTP_TEST_PORT && protocol.equalsIgnoreCase("https")) {
      useDefaultHttps = true
    }
    builder.append(protocol)
    builder.append("://")
    builder.append(url.getAuthority())
    if(!hasPortDefined(url) && !(port == DEFAULT_HTTP_PORT || hasPath(url)) && !useDefaultHttps) {
      builder.append(":")
      builder.append(port)
    }
    return builder.toString()
  }

  private def boolean hasPath(uri) {
    def path = uri.getPath().trim()
    path != "";
  }

  private def boolean hasPortDefined(uri) {
    return uri.getPort() != -1;
  }

  private def appendParameters(Map<String, Object> from, Map<String, Object> to) {
    notNull from, "Map to copy from"
    notNull to, "Map to copy to"
    from.each {key, value ->
      appendStandardParameter(to, key, value)
    }
  }

  private def appendListParameter(Map<String, String> to, String key, List<Object> values) {
    if(values == null || values.isEmpty()) {
      return;
    }

    def convertedValues = values.collect { serializeIfNeeded(it) }
    if (to.containsKey(key)) {
      def currentValue = to.get(key)
      if (currentValue instanceof List) {
        currentValue.addAll(convertedValues)
      } else {
        to.put(key, [currentValue, convertedValues].flatten())
      }
    } else {
      to.put(key, new LinkedList<Object>(convertedValues))
    }
  }


  private def appendStandardParameter(Map<String, Object> to, String key, Object value) {
    def newValue = serializeIfNeeded(value)
    if (to.containsKey(key)) {
      def currentValue = to.get(key)
      if (currentValue instanceof List) {
        currentValue << newValue
      } else {
        to.put(key, [currentValue, newValue])
      }
    } else {
      to.put(key, newValue)
    }
  }

  private def serializeIfNeeded(Object object) {
    isSerializableCandidate(object) ? ObjectMapping.serialize(object, requestContentType, null) : object
  }

  private def applyPathParamsAndSendRequest(Method method, String path, Object...pathParams) {
    notNull path, "path"
    notNull pathParams, "Path params"

    path = applyPathParamsIfNeeded(path, pathParams)
    invokeFilterChain(path, method, responseSpecification.assertionClosure)
  }

  private def String applyPathParamsIfNeeded(String path, Object... pathParams) {
    def suppliedPathParamSize = pathParams.size()
    def fieldPathParamSize = this.pathParameters.size()
    if(suppliedPathParamSize == 0 && fieldPathParamSize == 0) {
      return path
    } else if(suppliedPathParamSize > 0 && fieldPathParamSize > 0) {
      throw new IllegalArgumentException("You cannot specify both named and unnamed path params at the same time")
    } else {
      def matchPattern = ~/.*\{\w+\}.*/
      if (suppliedPathParamSize > 0) {
        def replacePattern = ~/\{\w+\}/
        int current = 0;
        pathParams.each {
          if (!path.matches(matchPattern)) {
            throw new IllegalArgumentException("Illegal number of path parameters. Expected $current, was $suppliedPathParamSize.")
          }
          current++
          path = path.replaceFirst(replacePattern, Matcher.quoteReplacement(it.toString()))
        }
      } else {
        this.pathParameters.each { key, value ->
          def literalizedKey = Matcher.quoteReplacement(key)
          def replacePattern = Pattern.compile("\\{$literalizedKey\\}")
          int current = 0;
          if(path.matches(".*\\{$literalizedKey\\}.*")) {
            path = path.replaceFirst(replacePattern, value.toString())
            current++
          } else {
            throw new IllegalArgumentException("You specified too many path parameters ($fieldPathParamSize).")
          }
        }
      }
      if (path.matches(matchPattern)) {
        throw new IllegalArgumentException("You specified too few path parameters to the request.")
      }
    }
    path
  }

  private String createFormParamBody(Map<String, Object> formParams)  {
    final StringBuilder body = new StringBuilder();
    for (Entry<String, Object> entry : formParams.entrySet()) {
      body.append(entry.getKey()).
              append("=").
              append(handleMultiValueParamsIfNeeded(entry)).
              append("&");
    }
    body.deleteCharAt(body.length()-1); //Delete last &
    return body.toString();
  }

  private def handleMultiValueParamsIfNeeded(Entry<String, Object> entry) {
    def value = entry.getValue()
    if (value instanceof List) {
      final StringBuilder multiValueList = new StringBuilder();
      value.eachWithIndex { val, index ->
        multiValueList.append(val.toString())
        if(index != value.size() - 1) {
          multiValueList.append("&").append(entry.getKey()).append("=")
        }
      }
      value = multiValueList.toString()
    }
    return value
  }

  def void setResponseSpecification(ResponseSpecification responseSpecification) {
    this.responseSpecification = responseSpecification
  }

  String getBaseUri() {
    return baseUri
  }

  String getBasePath() {
    return basePath
  }

  int getPort() {
    return port
  }

  def Map<String, Object> getFormParams() {
    return Collections.unmodifiableMap(formParameters)
  }

  def Map<String, Object> getPathParams() {
    return Collections.unmodifiableMap(pathParameters)
  }

  Map<String, Object> getRequestParams() {
    return Collections.unmodifiableMap(requestParameters)
  }

  Map<String, Object> getQueryParams() {
    return Collections.unmodifiableMap(queryParameters)
  }

  Headers getHeaders() {
    return requestHeaders
  }

  Cookies getCookies() {
    return cookies
  }

  def <T> T getBody() {
    return requestBody
  }

  List<Filter> getDefinedFilters() {
    return Collections.unmodifiableList(filters)
  }

  def RestAssuredConfig getConfig() {
    return restAssuredConfig
  }

  String getRequestContentType() {
    return contentType != null ? contentType instanceof String ? contentType : contentType.toString() : ANY.toString()
  }

  def RequestSpecification noFilters() {
    this.filters.clear()
    this
  }

  def <T extends Filter> RequestSpecification noFiltersOfType(Class<T> filterType) {
    notNull filterType, "Filter type"
    this.filters.removeAll {filterType.isAssignableFrom(it.getClass())}
    this
  }

/**
 * A copy of HTTP builders doRequest method with two exceptions.
 * <ol>
 *  <li>The exception is that the entity's content is not closed if no body matchers are specified.</li>
 *  <li>If headers contain a list of elements the headers are added and not overridden</li>
 *  </ol>
 */
  private Object restAssuredDoRequest(HTTPBuilder httpBuilder, RequestConfigDelegate delegate) {
    final HttpRequestBase reqMethod = delegate.getRequest();

    Object contentType = delegate.getContentType();
    String acceptContentTypes = contentType.toString();
    if ( contentType instanceof ContentType )
    acceptContentTypes = ((ContentType)contentType).getAcceptHeader();

    reqMethod.setHeader( "Accept", acceptContentTypes );
    reqMethod.setURI( delegate.getUri().toURI() );

    if ( reqMethod.getURI() == null)
    throw new IllegalStateException( "Request URI cannot be null" );

    // set any request headers from the delegate
    Map<?,?> headers = delegate.getHeaders();
    for ( Object key : headers.keySet() ) {
      Object val = headers.get( key );
      if ( key == null ) continue;
      if ( val == null ) {
        reqMethod.removeHeaders( key.toString() )
      } else {
        def keyAsString = key.toString()
        if(val instanceof Collection) {
          val = val.flatten().collect { it?.toString() }
          val.each {
            reqMethod.addHeader(keyAsString, it )
          }
        } else {
          reqMethod.setHeader( keyAsString, val.toString() );
        }
      }
    }

    final HttpResponseDecorator resp = new HttpResponseDecorator(
            httpBuilder.client.execute( reqMethod, delegate.getContext() ),
            delegate.getContext(), null );
    try {
      int status = resp.getStatusLine().getStatusCode();
      Closure responseClosure = delegate.findResponseHandler( status );

      final Object returnVal;
      Object[] closureArgs = null;
      switch ( responseClosure.getMaximumNumberOfParameters() ) {
        case 1 :
          returnVal = responseClosure.call( resp );
          break;
        case 2 : // parse the response entity if the response handler expects it:
          HttpEntity entity = resp.getEntity();
          try {
            if ( entity == null || entity.getContentLength() == 0 ) {
              returnVal = responseClosure.call( resp, null );
            } else {
              returnVal = responseClosure.call( resp,  httpBuilder.parseResponse( resp, contentType ));
            }
          } catch ( Exception ex ) {
            org.apache.http.Header h = entity.getContentType();
            String respContentType = h != null ? h.getValue() : null;
            throw new ResponseParseException( resp, ex );
          }
          break;
        default:
          throw new IllegalArgumentException(
                  "Response closure must accept one or two parameters" );
      }
      return returnVal;
    }
    finally {
      if(responseSpecification.hasBodyAssertionsDefined()) {
        HttpEntity entity = resp.getEntity();
        if ( entity != null ) entity.consumeContent();
      }
    }
  }

  private boolean isSerializableCandidate(object) {
    if(object == null) {
      return false
    }
    def clazz = object.getClass()
    return !(Number.class.isAssignableFrom(clazz) || String.class.isAssignableFrom(clazz)
            || GString.class.isAssignableFrom(clazz) || Boolean.class.isAssignableFrom(clazz)
            || Character.class.isAssignableFrom(clazz) || clazz.isEnum() ||
            Locale.class.isAssignableFrom(clazz) || Class.isAssignableFrom(clazz));
  }

  def private String mergeAndRemoveDoubleSlash(String thisOne, String otherOne) {
    thisOne = thisOne.trim()
    otherOne = otherOne.trim()
    final boolean otherOneStartsWithSlash = otherOne.startsWith(SLASH);
    final boolean thisOneEndsWithSlash = thisOne.endsWith(SLASH)
    if(thisOneEndsWithSlash && otherOneStartsWithSlash) {
      return thisOne + substringAfter(otherOne, SLASH);
    } else if(!thisOneEndsWithSlash && !otherOneStartsWithSlash && !(otherOne == "" ^ thisOne == "")) {
      return "$thisOne/$otherOne"
    }
    return thisOne + otherOne;
  }
}