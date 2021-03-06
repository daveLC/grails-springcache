/*
 * Copyright 2010 Rob Fletcher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.springcache.web

import grails.plugin.springcache.SpringcacheService
import net.sf.ehcache.constructs.web.filter.PageFragmentCachingFilter
import org.codehaus.groovy.grails.web.util.WebUtils
import org.slf4j.LoggerFactory
import javax.servlet.*
import javax.servlet.http.*
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED
import net.sf.ehcache.*
import net.sf.ehcache.constructs.blocking.*
import net.sf.ehcache.constructs.web.*
import org.codehaus.groovy.grails.web.servlet.*
import static org.codehaus.groovy.grails.web.servlet.HttpHeaders.*

class GrailsFragmentCachingFilter extends PageFragmentCachingFilter {

	private final log = LoggerFactory.getLogger(getClass())
	private final timingLog = LoggerFactory.getLogger("${getClass().name}.TIMINGS")
	SpringcacheService springcacheService
	CacheManager cacheManager

	private final ThreadLocal<FilterContext> contextHolder = new ThreadLocal<FilterContext>()
	static final String X_SPRINGCACHE_CACHED = "X-Springcache-Cached"

	/**
	 * Overrides doInit in CachingFilter to be a no-op. The superclass initializes a single cache that is used for all
	 * intercepted requests but we will select a cache at runtime based on the target controller/action.
	 */
	@Override
	void doInit(FilterConfig filterConfig) {
		// don't do anything - we need to get caches differently for each request
	}

	/**
	 * Overrides doFilter in PageFragmentCachingFilter to handle flushing and caching behaviour selectively depending
	 * on annotations on target controller.
	 */
	@Override protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
		initContext()
		try {
			if (context.shouldFlush()) {
				handleFlush(request)
				chain.doFilter(request, response)
			} else if (context.shouldCache()) {
				logRequestDetails(request, context, "Caching enabled for request")
				def pageInfo = buildPageInfo(request, response, chain)
				writeResponse(request, response, pageInfo)
			} else {
				log.debug "No cacheable annotation found for $request.method:$request.requestURI $context"
				chain.doFilter(request, response)
			}
		} finally {
			destroyContext()
		}
	}

	/**
	 * Overrides buildPageInfo in PageFragmentCachingFilter to use different cache depending on target controller rather
	 * than having the cache wired into the filter.
	 */
	@Override protected PageInfo buildPageInfo(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
		def timer = new Timer(getCachedUri(request))
		timer.start()
		boolean isCached = true

		def cache = springcacheService.getOrCreateBlockingCache(context.cacheName)

		def key = calculateKey(request)

		def pageInfo
		try {
			def element = cache.get(key)
			if (!element || element.isExpired() || !element.objectValue) {
				isCached = false
				try {
					// Page is not cached - build the response, cache it, and send to client
					pageInfo = buildPage(request, response, chain)
					if (pageInfo.isOk()) {
						if (pageInfo.cacheDirectives["no-cache"]) {
							log.debug "Response ok but Cache-Control: no-cache is present, not caching"
							releaseCacheLock(cache, key)
						} else {
							element = new Element(key, pageInfo)
							element.timeToLive = pageInfo.cacheDirectives["max-age"] ?: pageInfo.timeToLiveSeconds
							log.debug "Response ok. Adding to cache $cache.name with key $key and ttl $element.timeToLive"
							cache.put(element)
						}
					} else {
						log.debug "Response not ok ($pageInfo.statusCode). Putting null into cache $cache.name with key $key"
						releaseCacheLock(cache, key)
					}
				} catch (Throwable t) {
					// Must unlock the cache if the above fails. Will be logged at Filter
					releaseCacheLock(cache, key)
					throw t
				}
			} else {
				log.debug "Serving cached content for $key"
				pageInfo = element.objectValue

				// As the page is cached, we need to add an instance of the associated
				// controller to the request. This is required by GrailsLayoutDecoratorMapper
				// to pick the appropriate layout.
				if (context.cacheParameters.controllerName) {
					def controller = context.cacheParameters.controllerClass.newInstance()
					request.setAttribute(GrailsApplicationAttributes.CONTROLLER, controller)
				}
			}
		} catch (LockTimeoutException e) {
			//do not release the lock, because you never acquired it
			throw e
		}

		timer.stop(isCached)
		response.addHeader(X_SPRINGCACHE_CACHED, "$isCached")
		return pageInfo
	}

	/**
	 * Overrides buildPage in PageFragmentCachingFilter to use different cache depending on target controller and to do
	 * special handling for Grails include requests.
	 */
	@Override protected PageInfo buildPage(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
		// Invoke the next entity in the chain
		def outstr = new ByteArrayOutputStream()
		def wrapper = new GenericResponseWrapper(response, outstr)

		// TODO: split the special include handling out into a separate method
		def originalResponse = null
		def isInclude = WebUtils.isIncludeRequest(request)
		if (isInclude) {
			originalResponse = WrappedResponseHolder.wrappedResponse
			WrappedResponseHolder.wrappedResponse = wrapper
		}
		try {
			chain.doFilter(request, wrapper)
		} finally {
			if (isInclude) {
				WrappedResponseHolder.wrappedResponse = originalResponse
			}
		}
		wrapper.flush()

		long timeToLiveSeconds = cacheManager.getEhcache(context.cacheName).cacheConfiguration.timeToLiveSeconds

		def contentType = wrapper.contentType ?: response.contentType
		return new PageInfo(wrapper.status, contentType, wrapper.headers, wrapper.cookies,
				outstr.toByteArray(), false, timeToLiveSeconds)
	}

	/**
	 * Overrides writeResponse in CachingFilter to 1 - only set status, contentType, cookies, etc. if this is the "main"
	 * request and not an include. 2 - send a status code 304 if appropriate.
	 */
	@Override protected void writeResponse(HttpServletRequest request, HttpServletResponse response, PageInfo pageInfo) {
		if (!WebUtils.isIncludeRequest(request)) {
			int statusCode = determineResponseStatus(request, response, pageInfo)
			response.status = statusCode
			setContentType(response, pageInfo)
			setCookies(pageInfo, response)
			setHeaders(pageInfo, acceptsGzipEncoding(request), response)
		}
		writeResponse response, pageInfo
	}

	@Override protected CacheManager getCacheManager() {
		throw new UnsupportedOperationException("Not supported in this implementation")
	}

	@Override protected String calculateKey(HttpServletRequest request) {
		def keyGenerator = context.keyGenerator
		return keyGenerator.generateKey(context.cacheParameters).toString()
	}

	@Override protected boolean acceptsGzipEncoding(HttpServletRequest request) {
		false
	}

	private void handleFlush(HttpServletRequest request) {
		logRequestDetails(request, context, "Flushing request")
		springcacheService.flush(context.cacheNames)
	}

	private int determineResponseStatus(HttpServletRequest request, HttpServletResponse response, PageInfo pageInfo) {
		int statusCode = pageInfo.statusCode
		if (!pageInfo.isModified(request)) {
			log.debug "Content not modified since ${request.getHeader(IF_MODIFIED_SINCE)} sending 304"
			statusCode = SC_NOT_MODIFIED
		} else if (pageInfo.isMatch(request)) {
			log.debug "Content matches entity tag ${request.getHeader(IF_NONE_MATCH)} sending 304"
			statusCode = SC_NOT_MODIFIED
		}
		statusCode
	}

	private void logRequestDetails(HttpServletRequest request, FilterContext context, String message) {
		if (log.isDebugEnabled()) {
			log.debug "$message..."
			log.debug "    method = $request.method"
			log.debug "    requestURI = $request.requestURI"
			log.debug "    forwardURI = $request.forwardURI"
			if (WebUtils.isIncludeRequest(request)) {
				log.debug "    includeURI = ${request[WebUtils.INCLUDE_REQUEST_URI_ATTRIBUTE]}"
			}
			log.debug "    controller = $context.cacheParameters.controllerName"
			log.debug "    action = $context.cacheParameters.actionName"
			log.debug "    format = $request.format"
			log.debug "    params = $context.cacheParameters.params"
		}
	}

	private String getCachedUri(HttpServletRequest request) {
		if (WebUtils.isIncludeRequest(request)) {
			return request[WebUtils.INCLUDE_REQUEST_URI_ATTRIBUTE]
		}
		return request.requestURI
	}

	private void releaseCacheLock(BlockingCache cache, String key) {
		cache.put(new Element(key, null))
	}

	private void initContext() {
		contextHolder.set(new FilterContext())
	}

	private FilterContext getContext() {
		contextHolder.get()
	}

	private void destroyContext() {
		contextHolder.remove()
	}

}
