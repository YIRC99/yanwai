async (page) => {
  const version = (await page.title()).match(/\d+\.\d+\.\d+/)[0];
  const keys = ['01-cover', '02-entry', '03-background', '04-roles', '05-analysis', '06-close'];
  await page.setViewportSize({width:1080,height:1920});
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all([...document.images].map(img => img.decode()));
  });
  const regions = page.getByRole('region');
  if (await regions.count() !== keys.length) throw new Error('Unexpected scene count');
  const screenshots = [];
  for (let i=0; i<keys.length; i++) {
    const target = regions.nth(i);
    // Let the browser paint newly visible tiles before exporting a tall region.
    await target.scrollIntoViewIfNeeded();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const box = await target.boundingBox();
    if (box.width !== 1080 || box.height !== 1920) throw new Error('Unexpected scene dimensions');
    const file = `output/yanwai-${version}/cards/${keys[i]}.png`;
    await target.screenshot({path:file,animations:'disabled'});
    screenshots.push(file);
  }
  return screenshots;
}
