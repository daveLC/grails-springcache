<g:if test="${params.flip}"><testcaching:caching a="${params.a}" b="${params.b}" /></g:if><g:else><testcaching:caching b="${params.b}" a="${params.a}" /></g:else>