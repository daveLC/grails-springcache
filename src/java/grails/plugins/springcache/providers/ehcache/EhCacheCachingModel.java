package grails.plugins.springcache.providers.ehcache;

import grails.plugins.springcache.cache.CachingModel;

class EhCacheCachingModel extends CachingModel {

	private final String cacheName;

	public EhCacheCachingModel(String id, String cacheName) {
		super(id);
		this.cacheName = cacheName;
	}

	public String getCacheName() {
		return cacheName;
	}
}