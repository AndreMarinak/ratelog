// Fork: click-to-load trailers. The page only ships a thumbnail; the YouTube player loads on click.
(function () {
    if (window.ratelogTrailerReady) return;
    window.ratelogTrailerReady = true;

    var EMBED_PREFIX = 'https://www.youtube-nocookie.com/embed/';

    document.addEventListener('click', function (event) {
        var button = event.target.closest && event.target.closest('.trailer-launch');
        if (!button) return;
        var url = button.getAttribute('data-embed-url');
        if (!url || url.indexOf(EMBED_PREFIX) !== 0) return;

        var iframe = document.createElement('iframe');
        iframe.src = url;
        iframe.title = button.getAttribute('data-title') || 'Trailer';
        iframe.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';
        iframe.allowFullscreen = true;
        iframe.referrerPolicy = 'strict-origin-when-cross-origin';
        button.replaceWith(iframe);
    });
})();
