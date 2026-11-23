// Fork: click-to-load trailers. The page only ships a thumbnail; clicking it opens #trailerModal
// (through modal.js's data-open-modal) and the YouTube player is created then. Closing the modal
// removes the player, which also stops the video.
(function () {
    if (window.ratelogTrailerReady) return;
    window.ratelogTrailerReady = true;

    var EMBED_PREFIX = 'https://www.youtube-nocookie.com/embed/';

    function init() {
        var modal = document.getElementById('trailerModal');
        if (!modal) return;
        var player = modal.querySelector('.trailer-player');
        if (!player) return;

        modal.addEventListener('modal:open', function (event) {
            var trigger = event.detail && event.detail.trigger;
            var url = trigger && trigger.getAttribute('data-embed-url');
            if (!url || url.indexOf(EMBED_PREFIX) !== 0) return;

            var iframe = document.createElement('iframe');
            iframe.src = url;
            iframe.title = player.getAttribute('data-title') || 'Trailer';
            iframe.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';
            iframe.allowFullscreen = true;
            iframe.referrerPolicy = 'strict-origin-when-cross-origin';
            player.replaceChildren(iframe);
        });

        modal.addEventListener('modal:close', function () {
            player.replaceChildren();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
