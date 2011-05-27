/*
 * Copyright 2009 Rob Fletcher
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
package grails.plugin.springcache.aop

import grails.plugin.springcache.annotations.EvictCacheKey

import grails.plugin.springcache.*
import org.aspectj.lang.annotation.*
import org.slf4j.*
import org.springframework.context.*
import org.aspectj.lang.JoinPoint

@Aspect
public class EvictCacheKeyAspect implements ApplicationContextAware {

	private final Logger log = LoggerFactory.getLogger(EvictCacheKeyAspect.class)

	SpringcacheService springcacheService
	ApplicationContext applicationContext

	@After("@annotation(evictCacheKey)")
	void removeCacheKey(JoinPoint jp, EvictCacheKey evictCacheKey) {
		if (log.isDebugEnabled()) log.debug "Intercepted ${jp.toLongString()}"
		def cacheName = resolveCacheName(evictCacheKey)
		def key = CacheKey.generate(jp.target, evictCacheKey.methodName(), jp.args)
		springcacheService.evictKeyFromCache(cacheName, key)
	}

	private String resolveCacheName(EvictCacheKey evictCacheKey) {
		def baseName = evictCacheKey.cache()
		CacheResolver resolver = applicationContext[evictCacheKey.cacheResolver()]
		resolver.resolveCacheName(baseName)
	}

}