/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wcs2_0.response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.opengis.wcs20.DescribeCoverageType;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageDimensionInfo;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.DimensionInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.wcs.CoverageCleanerCallback;
import org.geoserver.wcs.WCSInfo;
import org.geoserver.wcs.responses.CoverageResponseDelegateFinder;
import org.geoserver.wcs.responses.GeoTIFFCoverageResponseDelegate;
import org.geoserver.wcs2_0.GetCoverage;
import org.geoserver.wcs2_0.WCS20Const;
import org.geoserver.wcs2_0.exception.WCS20Exception;
import org.geoserver.wcs2_0.util.EnvelopeAxesLabelsMapper;
import org.geoserver.wcs2_0.util.NCNameResourceCodec;
import org.geoserver.wcs2_0.util.RequestUtils;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.CRS.AxisOrder;
import org.geotools.util.logging.Logging;
import org.geotools.wcs.v2_0.WCS;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.datum.PixelInCell;
import org.vfny.geoserver.wcs.WcsException;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.NamespaceSupport;

/**
 * Based on the <code>org.geotools.xml.transform</code> framework, does the
 * job of encoding a WCS 2.0.1 DescribeCoverage document.
 * 
 * @author Emanuele Tajariol (etj) - GeoSolutions
 * @author Simone Giannecchini, GeoSolutions
 */
public class WCS20DescribeCoverageTransformer extends GMLTransformer {
    public static final Logger LOGGER = Logging.getLogger(WCS20DescribeCoverageTransformer.class
            .getPackage().getName());
    
    private MIMETypeMapper mimemapper;
    private WCSInfo wcs;

    private Catalog catalog;

    private CoverageResponseDelegateFinder responseFactory;
    
    /**
     * Creates a new WFSCapsTransformer object.
     * @param mimemapper 
     */
    public WCS20DescribeCoverageTransformer(WCSInfo wcs, Catalog catalog, CoverageResponseDelegateFinder responseFactory,EnvelopeAxesLabelsMapper envelopeDimensionsMapper, MIMETypeMapper mimemapper) {
        super(envelopeDimensionsMapper);
        this.wcs = wcs;
        this.catalog = catalog;
        this.responseFactory = responseFactory;
        this.mimemapper = mimemapper;
        setNamespaceDeclarationEnabled(false);
        setIndentation(2);
    }

    public WCS20DescribeCoverageTranslator createTranslator(ContentHandler handler) {
        return new WCS20DescribeCoverageTranslator(handler);
    }

    public class WCS20DescribeCoverageTranslator extends GMLTranslator {
        private DescribeCoverageType request;

        private String proxifiedBaseUrl;

        public WCS20DescribeCoverageTranslator(ContentHandler handler) {
            super(handler);
        }

        /**
         * Encode the object.

         */
        @Override
        public void encode(Object o) throws IllegalArgumentException {
            
            if (!(o instanceof DescribeCoverageType)) {
                throw new IllegalArgumentException(new StringBuffer("Not a DescribeCoverageType: ")
                        .append(o).toString());
            }

            this.request = (DescribeCoverageType) o;

            // collect coverages
            List<CoverageInfo> coverages = new ArrayList<CoverageInfo>();

            for (String encodedCoverageId : (List<String>)request.getCoverageId()) {
                LayerInfo layer = NCNameResourceCodec.getCoverage(catalog, encodedCoverageId);
                if(layer != null) {
                    coverages.add((CoverageInfo) layer.getResource());
                } else {
                    // if we get there there is an internal error, the coverage existence is
                    // checked before creating the transformer
                    throw new IllegalArgumentException("Failed to locate coverage " 
                            + encodedCoverageId + ", unexpected, the coverage existance has been " +
                            		"checked earlier in the request lifecycle");
                }
            }
            
            // register namespaces provided by extended capabilities
            NamespaceSupport namespaces = getNamespaceSupport();
            namespaces.declarePrefix("swe", "http://www.opengis.net/swe/2.0");
            namespaces.declarePrefix("wcsgs", "http://www.geoserver.org/wcsgs/2.0");
            for (WCS20CoverageMetadataProvider cp : extensions) {
                cp.registerNamespaces(namespaces);
            }


            // ok: build the response
            final AttributesImpl attributes = WCS20Const.getDefaultNamespaces();
            helper.registerNamespaces(getNamespaceSupport(), attributes);
            String location = buildSchemaLocation(request.getBaseUrl(), WCS.NAMESPACE, "http://schemas.opengis.net/wcs/2.0/wcsDescribeCoverage.xsd");
            attributes.addAttribute("", "xsi:schemaLocation", "xsi:schemaLocation", "", location);
            start("wcs:CoverageDescriptions", attributes);
            for (CoverageInfo ci : coverages) {
                try {
                    String encodedId = NCNameResourceCodec.encode(ci);
                    handleCoverageDescription(encodedId, ci);
                } catch (Exception e) {
                    throw new RuntimeException("Unexpected error occurred during describe coverage xml encoding", e);
                }
            }
            end("wcs:CoverageDescriptions");
        }
        
        String buildSchemaLocation(String schemaBaseURL, String... locations) {
            for (WCS20CoverageMetadataProvider cp : extensions) {
                locations = helper.append(locations, cp.getSchemaLocations(schemaBaseURL));
            }

            return helper.buildSchemaLocation(locations);
        }


        /**
         * 
         * @param ci
         */
        public void handleCoverageDescription(String encodedId, CoverageInfo ci) {

             try {
                // see if we have to handle time, elevation and additional dimensions
                WCSDimensionsHelper dimensionsHelper = null;
                MetadataMap metadata = ci.getMetadata();
                Map<String, DimensionInfo> dimensionsMap = WCSDimensionsHelper.getDimensionsFromMetadata(metadata);

                // Setup a dimension helper in case we found some dimensions for that coverage
                if (!dimensionsMap.isEmpty()) {
                    dimensionsHelper = new WCSDimensionsHelper(dimensionsMap, RequestUtils.getCoverageReader(ci), encodedId);
                }

                GridCoverage2DReader reader = (GridCoverage2DReader) ci.getGridCoverageReader(null, null);
                if (reader== null) {
                    throw new WCS20Exception("Unable to read sample coverage for " + ci.getName());
                }
                // get the crs and look for an EPSG code
                final CoordinateReferenceSystem crs = reader.getCoordinateReferenceSystem();
                List<String> axesNames = envelopeDimensionsMapper.getAxesNames(
                        reader.getOriginalEnvelope(), true);

                // lookup EPSG code
                Integer EPSGCode = null;
                try {
                    EPSGCode = CRS.lookupEpsgCode(crs, false);
                } catch (FactoryException e) {
                    throw new IllegalStateException("Unable to lookup epsg code for this CRS:"
                            + crs, e);
                }
                if (EPSGCode == null) {
                    throw new IllegalStateException("Unable to lookup epsg code for this CRS:"
                            + crs);
                }
                final String srsName = GetCoverage.SRS_STARTER + EPSGCode;
                // handle axes swap for geographic crs
                final boolean axisSwap = CRS.getAxisOrder(crs).equals(AxisOrder.EAST_NORTH);

                // encoding ID of the coverage
                final AttributesImpl coverageAttributes = new AttributesImpl();
                coverageAttributes.addAttribute("", "gml:id", "gml:id", "", encodedId);

                // starting encoding
                start("wcs:CoverageDescription", coverageAttributes);

                // handle domain
                final StringBuilder builder = new StringBuilder();
                for (String axisName : axesNames) {
                    builder.append(axisName).append(" ");
                }
                if (dimensionsHelper != null && dimensionsHelper.getElevationDimension() != null) {
                    builder.append("elevation ");
                }
                
                if (dimensionsHelper != null && dimensionsHelper.getTimeDimension() != null) {
                    builder.append("time ");
                }
                String axesLabel = builder.substring(0, builder.length() - 1);
                GeneralEnvelope envelope = reader.getOriginalEnvelope();
                handleBoundedBy(envelope, axisSwap, srsName, axesLabel, dimensionsHelper);

                // coverage id
                element("wcs:CoverageId", encodedId);

                // handle coverage function
                handleCoverageFunction((GridEnvelope2D) reader.getOriginalGridRange(), axisSwap);

                // metadata
                handleMetadata(ci, dimensionsHelper);

                // handle domain
                builder.setLength(0);
                axesNames = envelopeDimensionsMapper.getAxesNames(reader.getOriginalEnvelope(), false);
                for (String axisName : axesNames) {
                    builder.append(axisName).append(" ");
                }
                axesLabel = builder.substring(0, builder.length() - 1);
                GridGeometry2D gg = new GridGeometry2D(reader.getOriginalGridRange(), 
                        reader.getOriginalGridToWorld(PixelInCell.CELL_CENTER), reader.getCoordinateReferenceSystem());
                handleDomainSet(gg, 2, encodedId, srsName, axisSwap);

                // handle rangetype
                handleRangeType(ci.getDimensions());

                // service parameters
                handleServiceParameters(ci);

                end("wcs:CoverageDescription");
            } catch (Exception e) {
                throw new WcsException(e);
            } 
        }

        private GridSampleDimension[] getSampleDimensions(GridCoverage2DReader reader) throws Exception {
            GridCoverage2D coverage = null;
            try {
                coverage = RequestUtils.readSampleGridCoverage(reader);
                return coverage.getSampleDimensions();
            } finally {
                if(coverage != null) {
                    CoverageCleanerCallback.addCoverages(coverage);
                }
            }
            
        }

        private void handleServiceParameters(CoverageInfo ci) throws IOException {
            start("wcs:ServiceParameters");
            element("wcs:CoverageSubtype", "RectifiedGridCoverage");
            
            String mapNativeFormat = mimemapper.mapNativeFormat(ci);
            if(mapNativeFormat==null){
                // fall back on GeoTiff when a native format cannot be determined
                mapNativeFormat = GeoTIFFCoverageResponseDelegate.GEOTIFF_CONTENT_TYPE;
            }
            element("wcs:nativeFormat",mapNativeFormat);
            end("wcs:ServiceParameters");
        }

        /**
         * Encodes the RangeType as per the {@link DescribeCoverageType}WCS spec of the provided {@link GridCoverage2D}
         * 
         * e.g.:
         * 
         * <pre>
         * {@code
         * <gmlcov:rangeType>
         *    <swe:DataRecord>
         *        <swe:field name="singleBand">
         *           <swe:Quantity definition="http://www.opengis.net/def/property/OGC/0/Radiance">
         *               <gml:description>Panchromatic Channel</gml:description>
         *               <gml:name>single band</gml:name>
         *               <swe:uom code="W/cm2"/>
         *               <swe:constraint>
         *                   <swe:AllowedValues>
         *                       <swe:interval>0 255</swe:interval>
         *                       <swe:significantFigures>3</swe:significantFigures>
         *                   </swe:AllowedValues>
         *               </swe:constraint>
         *           </swe:Quantity>
         *        </swe:field>
         *    </swe:DataRecord>
         * </gmlcov:rangeType>
         * }
         * </pre>
         * 
         * @param gc2d the {@link GridCoverage2D} for which to encode the RangeType.
         */
        public void handleRangeType(final List<CoverageDimensionInfo> bands) {
            start("gmlcov:rangeType");
            start("swe:DataRecord");
            
            // handle bands
            for(CoverageDimensionInfo sd : bands){
                final AttributesImpl fieldAttr = new AttributesImpl();
                fieldAttr.addAttribute("", "name", "name", "", sd.getName());                
                start("swe:field",fieldAttr);
                
                start("swe:Quantity");
                
                // Description
                start("swe:description");
                chars(sd.getName()); // TODO can we make up something better??
                end("swe:description");

                // nil values
                List<Double> nullValues = sd.getNullValues();
                if (nullValues != null && !nullValues.isEmpty()) {
                    final int size = nullValues.size();
                    double[] noDataValues = new double[size];
                    for (int i = 0; i < size; i++) {
                        noDataValues[i] = nullValues.get(i);
                    }
                    handleSampleDimensionNilValues(null, noDataValues);
                }

                //UoM
                final AttributesImpl uomAttr = new AttributesImpl();
                final String unit =sd.getUnit();
                uomAttr.addAttribute("", "code", "code", "", unit == null ? "W.m-2.Sr-1" : unit); 
                start("swe:uom",uomAttr);
                end("swe:uom");
                
                // constraint on values
                start("swe:constraint");
                start("swe:AllowedValues");
                handleSampleDimensionRange(sd);// TODO make  this generic
                end("swe:AllowedValues");
                end("swe:constraint");
                
                end("swe:Quantity");
                end("swe:field");
            }
        
            end("swe:DataRecord");
            end("gmlcov:rangeType");
            
        }

    }

}
