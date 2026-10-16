package eu.elraro.openreader

/**
 * Script that lays Word pages out in the WebView: a page whose content is taller than the page (beyond a tolerance for
 * pages whose end Word recorded itself) is split into further pages, tables row by row. Measuring in the browser uses the
 * real font metrics, which a converter without a layout engine can't know.
 */
object Paginate {
    val JS = """
(function(){
  var TOL=36, done=false;
  function px(v){return parseFloat(v)||0}
  function eb(el){return el.getBoundingClientRect().bottom+px(getComputedStyle(el).marginBottom)}
  function kids(el,cls){return Array.prototype.filter.call(el.children,function(c){return c.classList.contains(cls)})}
  function limitOf(page){
    var cs=getComputedStyle(page);
    var lim=px(cs.minHeight)-px(cs.paddingTop)-px(cs.paddingBottom);
    kids(page,'hf').forEach(function(c){lim-=c.getBoundingClientRect().height});
    return lim;
  }
  function newPage(src){
    var p=src.cloneNode(false);
    Array.prototype.forEach.call(src.children,function(c){
      if(c.classList.contains('hf'))p.appendChild(c.cloneNode(true));
      else if(c.classList.contains('bd'))p.appendChild(c.cloneNode(false));
    });
    return p;
  }
  function paginate(page){
    var guard=0;
    while(guard++<400){
      var bd=kids(page,'bd')[0]; if(!bd)return;
      var lim=limitOf(page); if(lim<100)return;
      var top=bd.getBoundingClientRect().top;
      var items=Array.prototype.slice.call(bd.children);
      if(items.length<2&&!(items[0]&&items[0].tagName==='TABLE'))return;
      var last=items[items.length-1];
      if(eb(last)-top<=lim+TOL)return;
      var idx=-1;
      for(var i=0;i<items.length;i++){if(eb(items[i])-top>lim){idx=i;break}}
      if(idx<0)return;
      var moving=null;
      var it=items[idx];
      if(it.tagName==='TABLE'&&it.rows&&it.rows.length>1){
        var r=-1;
        for(var j=0;j<it.rows.length;j++){if(it.rows[j].getBoundingClientRect().bottom-top>lim){r=j;break}}
        if(r>0){
          var t2=it.cloneNode(false), cg=it.querySelector('colgroup');
          if(cg)t2.appendChild(cg.cloneNode(true));
          var tb=document.createElement('tbody'); t2.appendChild(tb);
          var rows=Array.prototype.slice.call(it.rows,r);
          rows.forEach(function(row){tb.appendChild(row)});
          moving=[t2].concat(items.slice(idx+1));
        }
      }
      if(!moving){
        if(idx===0)idx=1; // one block taller than a page: let it overflow
        if(idx>=items.length)return;
        moving=items.slice(idx);
      }
      var np=newPage(page), nb=kids(np,'bd')[0];
      moving.forEach(function(m){nb.appendChild(m)});
      page.parentNode.insertBefore(np,page.nextSibling);
      page=np;
    }
  }
  function run(){
    if(done)return; done=true;
    try{
      Array.prototype.slice.call(document.querySelectorAll('.page')).forEach(paginate);
      var pages=document.querySelectorAll('.page');
      Array.prototype.forEach.call(pages,function(p,i){
        Array.prototype.forEach.call(p.querySelectorAll('.fpg'),function(e){e.textContent=String(i+1)});
        Array.prototype.forEach.call(p.querySelectorAll('.fnp'),function(e){e.textContent=String(pages.length)});
      });
    }catch(e){}
    var total=document.querySelectorAll('.page').length;
    document.documentElement.setAttribute('data-paginated',String(total));
    document.title='pages:'+total;
  }
  if(document.fonts&&document.fonts.ready)document.fonts.ready.then(function(){setTimeout(run,50)});
  window.addEventListener('load',function(){setTimeout(run,1500)});
})();
"""
}
