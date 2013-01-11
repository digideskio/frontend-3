queryParams = () ->
  res = {}
  params = window.location.search.substring(1).split("&")
  for p in params
    [k,v] = p.split("=")
    res[k] = v
  res

window.queryParams = queryParams

class CircleViewModel
  constructor: ->
    @ab = (new ABTests(ab_test_definitions)).ab_tests
    @query_results_query = ko.observable(null)
    @query_results = ko.observableArray([])

  searchArticles: (vm, event) ->
    $.ajax
      url: "/search-articles"
      type: "GET"
      data:
        query: $("#query").val()
      success: (results) =>
        @query_results results.results
        @query_results_query results.query
    event.preventDefault()
    event.stopPropagation()
    false

  suggestArticles: (query, process) =>
    $.ajax
      url: "/autocomplete-articles"
      type: "GET"
      data:
        query: query
      success: (autocomplete) =>
        process autocomplete.suggestions
    null

window.CircleVM = new CircleViewModel

circle = $.sammy "body", ->
  # Page
  class Page
    constructor: (@name, @title) ->

    display: (cx) =>
      document.title = "Circle - " + @title

      # Render content
      @render(cx)

      # Land at the right anchor on the page
      # @scroll window.location.hash

      # Fetch page-specific libraries
      @lib() if @lib?

      ko.applyBindings(CircleVM)

    render: (cx) =>
      $("body").attr("id","#{@name}-page").html HAML['header'](renderContext)
      $("body").append HAML[@name](renderContext)
      $("body").append HAML['footer'](renderContext)

    scroll: (hash) =>
      if hash == '' or hash == '#' then hash = "body"
      $('html, body').animate({scrollTop: $(hash).offset().top}, 0)


  class Home extends Page
    render: (cx) =>
      super(cx)
      _kmq.push(['trackClickOnOutboundLink', '#join', 'hero join link clicked'])
      _kmq.push(['trackClickOnOutboundLink', '.kissAuthGithub', 'join link clicked'])
      _kmq.push(['trackClickOnOutboundLink', '#second-join', 'footer join link clicked'])
      _kmq.push(['trackSubmit', '#beta', 'beta form submitted'])
      _gaq.push(['_trackPageview', '/homepage'])

  # Doc
  class Docs extends Page
    rewrite_old_name: (name) =>
      switch name
        when "/common-problems" then ""
        when "/common-problems#intro" then ""
        when "/common-problems#file-ordering" then "/file-ordering"
        when "/common-problems#missing-log-dir" then "/missing-log-dir"
        when "/common-problems#missing-file" then "/missing-file"
        when "/common-problems#time-day" then "/time-day"
        when "/common-problems#time-seconds" then "/time-seconds"
        when "/common-problems#requires-admin" then "/requires-admin"
        when "/common-problems#oom" then "/oom"
        when "/common-problems#wrong-ruby-version" then "/wrong-ruby-version"
        when "/common-problems#dont-run" then "/dont-run"
        when "/common-problems#git-bundle-install" then "/git-bundle-install"
        when "/common-problems#git-pip-install" then "/git-pip-install"
        when "/common-problems#wrong-commands" then "/wrong-commands"
        when "/common-problems#bundler-latest" then "/bundler-latest"
        when "/common-problems#capybara-timeout" then "/capybara-timeout"
        when "/common-problems#clojure-12" then "/clojure-12"

        when "/faq" then ""
        when "/faq#permissions" then "/permissions"
        when "/faq#what-happens" then "/what-happens"
        when "/faq#look-at-code" then "/look-at_code"
        when "/faq#parallelism" then "/parallelism"
        when "/faq#versions" then "/environment"
        when "/faq#external-resources" then "/external-resources"
        when "/faq#cant-follow" then "/cant-follow"

        else false

    filename: (cx) =>
      name = cx.params.splat[0]
      if name
        name.replace(/^\//, '').replace(/\//g, '_').replace(/-/g, '_').replace(/#.*/, '')
      else
        "docs"

    article_info: (slug) =>
      node = $(window.HAML[slug]())
      uriFragment = slug.replace(/_/g, '-')
      {
        url: "/docs/#{uriFragment}",
        slug: slug,
        title: node.find('.title > h1').text().trim()
        subtitle: node.find('.title > h4').text().trim()
        icon: node.find('.title > h1 > i').attr('class')
      }

    find_articles_by_tag: (tag) =>
      articles = []
      for slug of HAML
        article_tags = null

        try
          ## a bit of a hack: tagged article templates are expected to *write* into their context,
          ## and here we read what's written.
          context = {}
          window.HAML[slug](context)
          article_tags = context['article_tags']
        catch error
          ## meaning: can't be rendered without more context. Should never be true of docs!
          article_tags = null

        if article_tags
          if tag in article_tags
            articles.push(@article_info slug)
      articles

    categories: (cx) =>
      categories = {}
      for slug of HAML
        category = null
        
        try
          ## a bit of a hack: category templates are expected to *write* into their context,
          ## and here we read what's written.
          context = {}
          window.HAML[slug](context)
          category = context['category']
        catch error
          ## meaning: can't be rendered without more context. Should never be true of docs!
          category = null

        if category
          categories[category] = @find_articles_by_tag(category)
      categories

    render: (cx) =>
      rewrite = @rewrite_old_name cx.params.splat[0]
      if rewrite != false
        return cx.redirect "/docs" + rewrite
      name = @filename cx
      $("body").attr("id","docs-page").html(HAML['header'](renderContext))
      $("body").append(HAML['title'](renderContext))
      $("#title h1").text("Documentation")
      $("body").append("<div id='content'><section class='article'></section></div>")
      $(".article")
        .append(HAML['categories']({categories: @categories(), page: name}))
        .append(HAML[name]({find_articles_by_tag: @find_articles_by_tag})) ## XXX: merge w/renderContext?
      $("body").append(HAML['footer'](renderContext))
      $("#query").typeahead {
        'source': window.CircleVM.suggestArticles
      }

  # Pages
  home = new Home("home", "Continuous Integration made easy")
  about = new Page("about", "About Us")
  privacy = new Page("privacy", "Privacy and Security")
  pricing = new Page("pricing", "Plans and Pricing")
  docs = new Docs("docs", "Documentation")

  # Define Libs
  highlight = =>
    if !hljs?
      $.getScript renderContext.assetsRoot + "/js/vendor/highlight.pack.js", =>
        $("pre code").each (i, e) => hljs.highlightBlock e

    else
      $("pre code").each (i, e) => hljs.highlightBlock e

  placeholder = =>
    if !Modernizr.input.placeholder
      $.getScript renderContext.assetsRoot + "/js/vendor/jquery.placeholder.js", =>
        $("input, textarea").placeholder()

  follow = =>
    $("#twitter-follow-template-div").empty()
    clone = $(".twitter-follow-template").clone()
    clone.removeAttr "style" # unhide the clone
    clone.attr "data-show-count", "false"
    clone.attr "class", "twitter-follow-button"
    $("#twitter-follow-template-div").append clone

    # reload twitter scripts to force them to run, converting a to iframe
    $.getScript "//platform.twitter.com/widgets.js"

  # Per-Page Libs
  home.lib = =>
    trigger = =>
      $("#testimonials").waypoint ((event, direction) ->
        $("#testimonials").addClass("scrolled")
      ),
        offset: "80%"
    trigger()
    placeholder()
    follow()

  docs.lib = =>
    follow()
    sidebar = =>
      $("ul.topics").stickyMojo
        footerID: "#footer"
        contentID: ".article article"
    highlight()
    # sidebar()

  about.lib = =>
    placeholder()
    follow()

  pricing.lib = =>
    $('html').popover
      html: true
      placement: "bottom"
      template: '<div class="popover billing-popover"><div class="popover-inner"><h3 class="popover-title"></h3><div class="popover-content"><p></p></div></div></div>'
      delay: 0
      trigger: "hover"
      selector: ".more-info"


  # Twitter Follow

  # Google analytics
  @bind 'event-context-after', ->
    if window._gaq? # we dont use ga in test mode
      window._gaq.push @path

  # Airbrake
  @bind 'error', (e, data) ->
    if data? and data.error? and window.Airbrake?
      window.Airbrake.captureException data.error

  # Kissmetrics
  if renderContext.showJoinLink
    _kmq.push(['record', "showed join link"])


  # Navigation
  @get "^/docs(.*)", (cx) -> docs.display(cx)
  @get "^/about.*", (cx) -> about.display(cx)
  @get "^/privacy.*", (cx) -> privacy.display(cx)
  @get "^/pricing.*", (cx) -> pricing.display(cx)
  @get "^/", (cx) -> home.display(cx)
  @post "^/notify", -> true # allow to propagate
  @post "^/about/contact", -> true # allow to propagate
  @get "^/.*", (cx) -> # catch-all for error pages
    if renderContext.status
      error = renderContext.status
      url = renderContext.githubPrivateAuthURL
      titles =
        401: "Login required"
        404: "Page not found"
        500: "Internal server error"

      messages =
        401: "<a href=\"#{url}\">You must <b>log in</b> to view this page.</a>"
        404: "We're sorry, but that page doesn't exist."
        500: "We're sorry, but something broke."

      title = titles[error] or "Something unexpected happened"
      message = messages[error] or "Something completely unexpected happened"

      # Set the title
      document.title = "Circle - " + title

      # Display page
      $("body").attr("id","error").html HAML['header'](renderContext)
      $("body").append HAML['error'](title: title, error: renderContext.status, message: message)
      $('body > div').wrapAll('<div id="wrap"/>');
      $("body").append HAML['footer'](renderContext)


# Global polyfills
if $.browser.msie and $.browser.version > 6 and $.browser.version < 9
  $.getScript(renderContext.assetsRoot + "/js/vendor/selectivizr-1.0.2.js")
# `!function(d,s,id){var js,fjs=d.getElementsByTagName(s)[0];if(!d.getElementById(id)){js=d.createElement(s);js.id=id;js.src="//platform.twitter.com/widgets.js";fjs.parentNode.insertBefore(js,fjs);}}(document,"script","twitter-wjs");`


# Run the application
$ ->
  circle.run window.location.pathname.replace(/\/$/, '')
