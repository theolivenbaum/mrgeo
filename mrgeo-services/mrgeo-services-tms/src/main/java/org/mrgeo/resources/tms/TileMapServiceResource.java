/*
 * Copyright 2009-2016 DigitalGlobe, Inc.
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
 * See the License for the specific language governing permissions and limitations under the License.
 *
 */

/**
 *
 */
package org.mrgeo.resources.tms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.mrgeo.colorscale.ColorScale;
import org.mrgeo.colorscale.ColorScaleManager;
import org.mrgeo.colorscale.applier.ColorScaleApplier;
import org.mrgeo.core.MrGeoConstants;
import org.mrgeo.data.ProviderProperties;
import org.mrgeo.data.raster.RasterUtils;
import org.mrgeo.data.tile.TileNotFoundException;
import org.mrgeo.image.MrsImageException;
import org.mrgeo.image.MrsPyramidMetadata;
import org.mrgeo.services.Configuration;
import org.mrgeo.services.SecurityUtils;
import org.mrgeo.services.mrspyramid.rendering.ImageHandlerFactory;
import org.mrgeo.services.mrspyramid.rendering.ImageRenderer;
import org.mrgeo.services.mrspyramid.rendering.ImageResponseWriter;
import org.mrgeo.services.mrspyramid.rendering.TiffImageRenderer;
import org.mrgeo.services.tms.TmsService;
import org.mrgeo.utils.HadoopUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.activation.MimetypesFileTypeMap;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

/**
 *
 */
@Path("/tms")
public class TileMapServiceResource
{

private static final Logger log = LoggerFactory.getLogger(TileMapServiceResource.class);
private static final MimetypesFileTypeMap mimeTypeMap = new MimetypesFileTypeMap();
private static final String VERSION = "1.0.0";
private static final String SRS = "EPSG:4326";
private static final String GENERAL_ERROR = "An error occurred in Tile Map Service";
private static String imageBaseDir = HadoopUtils.getDefaultImageBaseDirectory();
//public static String KML_VERSION = "http://www.opengis.net/kml/2.2";
//public static String KML_EXTENSIONS = "http://www.google.com/kml/ext/2.2";
//public static String KML_MIME_TYPE = "application/vnd.google-earth.kml+xml";

@Context
TmsService service;
static Properties props;

static
{
  init();
}

private static synchronized void init()
{
  try
  {
    if (props == null)
    {
      props = Configuration.getInstance().getProperties();
    }
  }
  catch (final IllegalStateException e)
  {
    log.error(MrGeoConstants.MRGEO_HDFS_IMAGE + " must be specified in the MrGeo configuration file (" +
        e.getMessage() + ")");
  }
}


protected static Response createEmptyTile(final ImageResponseWriter writer, final int width,
    final int height)
{
  // return an empty image
  final int dataType;
  if (writer.getResponseMimeType().equals("image/jpeg"))
  {
    dataType = BufferedImage.TYPE_3BYTE_BGR;
  }
  else
  {
    // dataType = BufferedImage.TYPE_INT_ARGB;
    dataType = BufferedImage.TYPE_4BYTE_ABGR;
  }

  final BufferedImage bufImg = new BufferedImage(width, height, dataType);
  final Graphics2D g = bufImg.createGraphics();
  g.setColor(new Color(0, 0, 0, 0));
  g.fillRect(0, 0, width, height);
  g.dispose();

  return writer.write(bufImg.getData()).build();
}

protected static Document mrsPyramidMetadataToTileMapXml(final String raster, final String url,
    final MrsPyramidMetadata mpm) throws ParserConfigurationException
{
    /*
     * String tileMap = "<?xml version='1.0' encoding='UTF-8' ?>" +
     * "<TileMap version='1.0.0' tilemapservice='http://localhost/mrgeo-services/api/tms/1.0.0'>" +
     * "  <Title>AfPk Elevation V2</Title>" + "  <Abstract>A test of V2 MrsPyramid.</Abstract>"
     * + "  <SRS>EPSG:4326</SRS>" + "  <BoundingBox minx='68' miny='33' maxx='72' maxy='35' />" +
     * "  <Origin x='68' y='33' />" +
     * "  <TileFormat width='512' height='512' mime-type='image/tiff' extension='tif' />" +
     * "  <TileSets profile='global-geodetic'>" +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/1' units-per-pixel='0.3515625' order='1' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/2' units-per-pixel='0.17578125' order='2' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/3' units-per-pixel='0.08789063' order='3' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/4' units-per-pixel='0.08789063' order='4' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/5' units-per-pixel='0.08789063' order='5' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/6' units-per-pixel='0.08789063' order='6' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/7' units-per-pixel='0.08789063' order='7' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/8' units-per-pixel='0.08789063' order='8' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/9' units-per-pixel='0.08789063' order='9' />"
     * +
     * "    <TileSet href='http://localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2/10' units-per-pixel='0.08789063' order='10' />"
     * + "  </TileSets>" + "</TileMap>";
     */

  final DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
  final DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

  // root elements
  final Document doc = docBuilder.newDocument();
  final Element rootElement = doc.createElement("TileMap");
  doc.appendChild(rootElement);
  final Attr v = doc.createAttribute("version");
  v.setValue(VERSION);
  rootElement.setAttributeNode(v);
  final Attr tilemapservice = doc.createAttribute("tilemapservice");
  tilemapservice.setValue(normalizeUrl(normalizeUrl(url).replace(raster, "")));
  rootElement.setAttributeNode(tilemapservice);

  // child elements
  final Element title = doc.createElement("Title");
  title.setTextContent(raster);
  rootElement.appendChild(title);

  final Element abst = doc.createElement("Abstract");
  abst.setTextContent("");
  rootElement.appendChild(abst);

  final Element srs = doc.createElement("SRS");
  srs.setTextContent(SRS);
  rootElement.appendChild(srs);

  final Element bbox = doc.createElement("BoundingBox");
  rootElement.appendChild(bbox);
  final Attr minx = doc.createAttribute("minx");
  minx.setValue(String.valueOf(mpm.getBounds().w));
  bbox.setAttributeNode(minx);
  final Attr miny = doc.createAttribute("miny");
  miny.setValue(String.valueOf(mpm.getBounds().s));
  bbox.setAttributeNode(miny);
  final Attr maxx = doc.createAttribute("maxx");
  maxx.setValue(String.valueOf(mpm.getBounds().e));
  bbox.setAttributeNode(maxx);
  final Attr maxy = doc.createAttribute("maxy");
  maxy.setValue(String.valueOf(mpm.getBounds().n));
  bbox.setAttributeNode(maxy);

  final Element origin = doc.createElement("Origin");
  rootElement.appendChild(origin);
  final Attr x = doc.createAttribute("x");
  x.setValue(String.valueOf(mpm.getBounds().w));
  origin.setAttributeNode(x);
  final Attr y = doc.createAttribute("y");
  y.setValue(String.valueOf(mpm.getBounds().s));
  origin.setAttributeNode(y);

  final Element tileformat = doc.createElement("TileFormat");
  rootElement.appendChild(tileformat);
  final Attr w = doc.createAttribute("width");
  w.setValue(String.valueOf(mpm.getTilesize()));
  tileformat.setAttributeNode(w);
  final Attr h = doc.createAttribute("height");
  h.setValue(String.valueOf(mpm.getTilesize()));
  tileformat.setAttributeNode(h);
  final Attr mt = doc.createAttribute("mime-type");
  mt.setValue("image/tiff");
  tileformat.setAttributeNode(mt);
  final Attr ext = doc.createAttribute("extension");
  ext.setValue("tif");
  tileformat.setAttributeNode(ext);

  final Element tilesets = doc.createElement("TileSets");
  rootElement.appendChild(tilesets);
  final Attr profile = doc.createAttribute("profile");
  profile.setValue("global-geodetic");
  tilesets.setAttributeNode(profile);

  for (int i = 0; i <= mpm.getMaxZoomLevel(); i++)
  {
    final Element tileset = doc.createElement("TileSet");
    tilesets.appendChild(tileset);
    final Attr href = doc.createAttribute("href");
    href.setValue(normalizeUrl(normalizeUrl(url)) + "/" + i);
    tileset.setAttributeNode(href);
    final Attr upp = doc.createAttribute("units-per-pixel");
    upp.setValue(String.valueOf(180d / 256d / Math.pow(2, i)));
    tileset.setAttributeNode(upp);
    final Attr order = doc.createAttribute("order");
    order.setValue(String.valueOf(i));
    tileset.setAttributeNode(order);
  }

  return doc;
}



protected static Document mrsPyramidToTileMapServiceXml(final String url,
    final List<String> pyramidNames) throws ParserConfigurationException,
    DOMException, UnsupportedEncodingException
{
    /*
     * String tileMapService = "<?xml version='1.0' encoding='UTF-8' ?>" +
     * "<TileMapService version='1.0.0' services='http://localhost/mrgeo-services/api/tms/'>" +
     * "  <Title>Example Tile Map Service</Title>" +
     * "  <Abstract>This is a longer description of the example tiling map service.</Abstract>" +
     * "  <TileMaps>" + "    <TileMap " + "      title='AfPk Elevation V2' " +
     * "      srs='EPSG:4326' " + "      profile='global-geodetic' " +
     * "      href='http:///localhost/mrgeo-services/api/tms/1.0.0/AfPkElevationV2' />" +
     * "  </TileMaps>" + "</TileMapService>";
     */

  final DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
  final DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

  // root elements
  final Document doc = docBuilder.newDocument();
  final Element rootElement = doc.createElement("TileMapService");
  doc.appendChild(rootElement);
  final Attr v = doc.createAttribute("version");
  v.setValue(VERSION);
  rootElement.setAttributeNode(v);
  final Attr service = doc.createAttribute("services");
  service.setValue(normalizeUrl(normalizeUrl(url).replace(VERSION, "")));
  rootElement.setAttributeNode(service);

  // child elements
  final Element title = doc.createElement("Title");
  title.setTextContent("Tile Map Service");
  rootElement.appendChild(title);

  final Element abst = doc.createElement("Abstract");
  abst.setTextContent("MrGeo MrsPyramid rasters available as TMS");
  rootElement.appendChild(abst);

  final Element tilesets = doc.createElement("TileMaps");
  rootElement.appendChild(tilesets);

  Collections.sort(pyramidNames);
  for (final String p : pyramidNames)
  {
    final Element tileset = doc.createElement("TileMap");
    tilesets.appendChild(tileset);
    final Attr href = doc.createAttribute("href");
    href.setValue(normalizeUrl(url) + "/" + URLEncoder.encode(p, "UTF-8"));
    tileset.setAttributeNode(href);
    final Attr maptitle = doc.createAttribute("title");
    maptitle.setValue(p);
    tileset.setAttributeNode(maptitle);
    final Attr srs = doc.createAttribute("srs");
    srs.setValue(SRS);
    tileset.setAttributeNode(srs);
    final Attr profile = doc.createAttribute("profile");
    profile.setValue("global-geodetic");
    tileset.setAttributeNode(profile);
  }

  return doc;
}

protected static String normalizeUrl(final String url)
{
  String newUrl;
  newUrl = (url.lastIndexOf("/") == url.length() - 1) ? url.substring(0, url.length() - 1) : url;
  return newUrl;
}

protected static Document rootResourceXml(final String url) throws ParserConfigurationException
{
    /*
     * <?xml version="1.0" encoding="UTF-8" ?> <Services> <TileMapService
     * title="MrGeo Tile Map Service" version="1.0.0"
     * href="http://localhost:8080/mrgeo-services/api/tms/1.0.0" /> </Services>
     */

  final DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
  final DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
  final Document doc = docBuilder.newDocument();
  final Element rootElement = doc.createElement("Services");
  doc.appendChild(rootElement);
  final Element tms = doc.createElement("TileMapService");
  rootElement.appendChild(tms);
  final Attr title = doc.createAttribute("title");
  title.setValue("MrGeo Tile Map Service");
  tms.setAttributeNode(title);
  final Attr v = doc.createAttribute("version");
  v.setValue(VERSION);
  tms.setAttributeNode(v);
  final Attr href = doc.createAttribute("href");
  href.setValue(normalizeUrl(url) + "/" + VERSION);
  tms.setAttributeNode(href);

  return doc;
}

@GET
@Produces("text/xml")
public Response getRootResource(@Context final HttpServletRequest hsr)
{
  try
  {
    final String url = hsr.getRequestURL().toString();
    final Document doc = rootResourceXml(url);
    final DOMSource source = new DOMSource(doc);
    return Response.ok(source, "text/xml").header("Content-type", "text/xml").build();

  }
  catch (final ParserConfigurationException ex)
  {
    return Response.status(Status.INTERNAL_SERVER_ERROR).entity(GENERAL_ERROR).build();
  }

}


@SuppressFBWarnings(value = "JAXRS_ENDPOINT", justification = "verified")
@SuppressWarnings("static-method")
@GET
@Produces("image/*")
@Path("{version}/{raster}/{z}/{x}/{y}.{format}")
public Response getTile(@PathParam("version") final String version,
    @PathParam("raster") String pyramid, @PathParam("z") final Integer z,
    @PathParam("x") final Integer x, @PathParam("y") final Integer y,
    @PathParam("format") final String format,
    @QueryParam("color-scale-name") final String colorScaleName,
    @QueryParam("color-scale") final String colorScale, @QueryParam("min") final Double min,
    @QueryParam("max") final Double max,
    @DefaultValue("1") @QueryParam("maskMax") final Double maskMax,
    @QueryParam("mask") final String mask)
{

  final ImageRenderer renderer;
  Raster raster;

  try
  {
    renderer = (ImageRenderer) ImageHandlerFactory.getHandler(format, ImageRenderer.class);

    // TODO: Need to construct provider properties from the WebRequest using
    // a new security layer and pass those properties.
    // Apply mask if requested
    ProviderProperties providerProperties = SecurityUtils.getProviderProperties();
    if (mask != null && !mask.isEmpty())
    {
      raster = renderer.renderImage(pyramid, x, y, z, mask, maskMax, providerProperties);
    }
    else
    {
      raster = renderer.renderImage(pyramid, x, y, z, providerProperties);
    }
    if (!(renderer instanceof TiffImageRenderer) && raster.getNumBands() != 3 &&
        raster.getNumBands() != 4)
    {
      ColorScale cs = null;
      if (colorScaleName != null)
      {
        cs = ColorScaleManager.fromName(colorScaleName, props);
      }
      else if (colorScale != null)
      {
        cs = ColorScaleManager.fromJSON(colorScale);
      }
//        else
//        {
//          cs = ColorScaleManager.fromPyramid(pyramid, driver);
//        }

      final double[] extrema = renderer.getExtrema();

      // Check for min/max override values from the request
      if (min != null)
      {
        extrema[0] = min;
      }
      if (max != null)
      {
        extrema[1] = max;
      }

      raster = ((ColorScaleApplier) ImageHandlerFactory.getHandler(format,
          ColorScaleApplier.class)).applyColorScale(raster, cs, extrema, renderer
          .getDefaultValues());
    }

    // Apply mask if requested
//      if (mask != null && !mask.isEmpty())
//      {
//        try
//        {
//          final MrsImagePyramidMetadata maskMetadata = service.getMetadata(mask);
//
//          final Raster maskRaster = renderer.renderImage(mask, x, y, z, props, driver);
//          final WritableRaster wr = RasterUtils.makeRasterWritable(raster);
//
//          final int band = 0;
//          final double nodata = maskMetadata.getDefaultValue(band);
//
//          for (int w = 0; w < maskRaster.getWidth(); w++)
//          {
//            for (int h = 0; h < maskRaster.getHeight(); h++)
//            {
//              final double maskPixel = maskRaster.getSampleDouble(w, h, band);
//              if (maskPixel > maskMax || Double.compare(maskPixel, nodata) == 0)
//              {
//                wr.setSample(w, h, band, nodata);
//              }
//            }
//          }
//        }
//        catch (final TileNotFoundException ex)
//        {
//          raster = RasterUtils.createEmptyRaster(raster.getWidth(), raster.getHeight(), raster
//            .getNumBands(), raster.getTransferType(), 0);
//        }
//      }

    return ((ImageResponseWriter) ImageHandlerFactory.getHandler(format,
        ImageResponseWriter.class)).write(raster, renderer.getDefaultValues()).build();

  }
  catch (final IllegalArgumentException e)
  {
    return Response.status(Status.BAD_REQUEST).entity("Unsupported image format - " + format)
        .build();
  }
  catch (final IOException e)
  {
    return Response.status(Status.NOT_FOUND).entity("Tile map not found - " + pyramid).build();
  }
  catch (final MrsImageException e)
  {
    return Response.status(Status.NOT_FOUND).entity("Tile map not found - " + pyramid + ": " + z)
        .build();
  }
  catch (final TileNotFoundException e)
  {
    // return Response.status(Status.NOT_FOUND).entity("Tile not found").build();
    try
    {
      final MrsPyramidMetadata metadata = service.getMetadata(pyramid);

      return createEmptyTile(((ImageResponseWriter) ImageHandlerFactory.getHandler(format,
          ImageResponseWriter.class)), metadata.getTilesize(), metadata.getTilesize());
    }
    catch (final Exception e1)
    {
      log.error("Exception occurred creating blank tile " + pyramid + "/" + z + "/" + x + "/" +
          y + "." + format, e1);
    }
  }
  catch (final ColorScale.BadJSONException e)
  {
    return Response.status(Status.NOT_FOUND).entity("Unable to parse color scale JSON").build();

  }
  catch (final ColorScale.BadSourceException e)
  {
    return Response.status(Status.NOT_FOUND).entity("Unable to open color scale file").build();
  }
  catch (final ColorScale.BadXMLException e)
  {
    return Response.status(Status.NOT_FOUND).entity("Unable to parse color scale XML").build();
  }
  catch (final ColorScale.ColorScaleException e)
  {
    return Response.status(Status.NOT_FOUND).entity("Unable to open color scale").build();
  }
  catch (final Exception e)
  {
    log.error("Exception occurred getting tile " + pyramid + "/" + z + "/" + x + "/" + y + "." +
        format, e);
  }

  return Response.status(Status.INTERNAL_SERVER_ERROR).entity(GENERAL_ERROR).build();
}


@SuppressFBWarnings(value = "JAXRS_ENDPOINT", justification = "verified")
@GET
@Produces("text/xml")
@Path("/{version}/{raster}")
public Response getTileMap(@PathParam("version") final String version,
    @PathParam("raster") String raster, @Context final HttpServletRequest hsr)
{
  try
  {
    final String url = hsr.getRequestURL().toString();
    // Check cache for metadata, if not found read from pyramid
    // and store in cache
    final MrsPyramidMetadata mpm = service.getMetadata(raster);
    final Document doc = mrsPyramidMetadataToTileMapXml(raster, url, mpm);
    final DOMSource source = new DOMSource(doc);

    return Response.ok(source, "text/xml").header("Content-type", "text/xml").build();

  }
  catch (final ExecutionException e)
  {
    log.error("MrsPyramid " + raster + " not found", e);
    return Response.status(Status.NOT_FOUND).entity("Tile map not found - " + raster).build();
  }
  catch (final ParserConfigurationException ex)
  {
    return Response.status(Status.INTERNAL_SERVER_ERROR).entity(GENERAL_ERROR).build();
  }
}

@SuppressFBWarnings(value = "JAXRS_ENDPOINT", justification = "verified")
@GET
@Produces("text/xml")
@Path("/{version}")
public Response getTileMapService(@PathParam("version") final String version,
    @Context final HttpServletRequest hsr)
{
  try
  {
    final String url = hsr.getRequestURL().toString();
    final Document doc = mrsPyramidToTileMapServiceXml(url, service.listImages());
    final DOMSource source = new DOMSource(doc);

    return Response.ok(source, "text/xml").header("Content-type", "text/xml").build();

  }
  catch (final IOException e)
  {
    log.error("File system exception for " + imageBaseDir, e);
    return Response.status(Status.INTERNAL_SERVER_ERROR).entity(GENERAL_ERROR).build();
  }
  catch (final ParserConfigurationException ex)
  {
    return Response.status(Status.INTERNAL_SERVER_ERROR).entity(GENERAL_ERROR).build();
  }
}

protected Response returnEmptyTile(final int width, final int height,
    final String format) throws Exception
{
  //return an empty image
  ImageResponseWriter writer = (ImageResponseWriter)ImageHandlerFactory.getHandler(format, ImageResponseWriter.class);
  ByteArrayOutputStream baos = new ByteArrayOutputStream();

  int bands;
  Double[] nodatas;
  if (format.equalsIgnoreCase("jpg") || format.equalsIgnoreCase("jpeg") )
  {
    bands = 3;
    nodatas = new Double[]{0.0,0.0,0.0};
  } else
  {
    bands = 4;
    nodatas = new Double[]{0.0,0.0,0.0,0.0};
  }

  Raster raster = RasterUtils.createEmptyRaster(width, height, bands, DataBuffer.TYPE_BYTE, nodatas);
  writer.writeToStream(raster, ArrayUtils.toPrimitive(nodatas), baos);
  byte[] imageData = baos.toByteArray();
  IOUtils.closeQuietly(baos);

  final String type = mimeTypeMap.getContentType("output." + format);
  return Response.ok(imageData).header("Content-Type", type).build();

  // A 404 - Not Found response may be the most appropriate, but results in pink tiles,
  // maybe change that behavior on the OpenLayers client?
  // return Response.status( Response.Status.NOT_FOUND).build();

}

}
