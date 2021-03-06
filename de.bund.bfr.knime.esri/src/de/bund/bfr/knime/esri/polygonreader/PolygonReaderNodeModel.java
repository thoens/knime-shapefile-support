package de.bund.bfr.knime.esri.polygonreader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;

import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.store.ContentFeatureCollection;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.projection.MapProjection;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.collection.CollectionCellFactory;
import org.knime.core.data.collection.ListCell;
import org.knime.core.data.def.BooleanCell;
import org.knime.core.data.def.BooleanCell.BooleanCellFactory;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.DoubleCell;
import org.knime.core.data.def.IntCell;
import org.knime.core.data.def.StringCell;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.defaultnodesettings.SettingsModelBoolean;
import org.knime.core.node.defaultnodesettings.SettingsModelOptionalString;
import org.knime.core.node.defaultnodesettings.SettingsModelString;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeType;
import org.opengis.referencing.operation.MathTransform;

import de.bund.bfr.knime.esri.EsriUtils;

/**
 * This is the model implementation of PolygonReader.
 * 
 * 
 * @author Christian Thoens
 */
public class PolygonReaderNodeModel extends NodeModel {

	protected static final String CFG_SHP_FILE = "ShpFile";
	protected static final String CFG_ROW_ID_PREFIX = "RowIdPredix";
	protected static final String CFG_GET_EXTERIOR_POLYGON = "GetExteriorPolygon";
	protected static final String CFG_SPLIT_POLYGONS_WITH_HOLES = "SplitPolygonsWithHoles";
	protected static final String CFG_CHARSET = "Charset";

	protected static final boolean DEFAULT_GET_EXTERIOR_POLYGON = false;
	protected static final boolean DEFAULT_SPLIT_POLYGONS_WITH_HOLES = true;
	protected static final String DEFAULT_CHARSET = StandardCharsets.UTF_8.name();

	private static final String LATITUDE_COLUMN = "Latitude";
	private static final String LONGITUDE_COLUMN = "Longitude";

	private SettingsModelString shpFile;
	private SettingsModelOptionalString rowIdPredix;
	private SettingsModelBoolean getExteriorPolygon;
	private SettingsModelBoolean splitPolygonsWithHoles;
	private SettingsModelString charset;

	/**
	 * Constructor for the node model.
	 */
	protected PolygonReaderNodeModel() {
		super(0, 2);
		shpFile = new SettingsModelString(CFG_SHP_FILE, null);
		rowIdPredix = new SettingsModelOptionalString(CFG_ROW_ID_PREFIX, null, false);
		getExteriorPolygon = new SettingsModelBoolean(CFG_GET_EXTERIOR_POLYGON, DEFAULT_GET_EXTERIOR_POLYGON);
		splitPolygonsWithHoles = new SettingsModelBoolean(CFG_SPLIT_POLYGONS_WITH_HOLES,
				DEFAULT_SPLIT_POLYGONS_WITH_HOLES);
		charset = new SettingsModelString(CFG_CHARSET, DEFAULT_CHARSET);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected BufferedDataTable[] execute(final BufferedDataTable[] inData, final ExecutionContext exec)
			throws Exception {
		MapProjection.SKIP_SANITY_CHECKS = true;

		MathTransform transform;

		try {
			transform = CRS.findMathTransform(EsriUtils.getCoordinateSystem(shpFile.getStringValue()),
					CRS.decode("EPSG:4326"), true);
		} catch (FileNotFoundException | NoSuchFileException e) {
			// Do not use transform
			transform = null;
		}

		ShapefileDataStore dataStore = EsriUtils.getDataStore(shpFile.getStringValue(), charset.getStringValue());
		ContentFeatureCollection collection = dataStore.getFeatureSource().getFeatures();
		DataTableSpec[] spec = createSpec(collection.getSchema());
		DataTableSpec spec1 = spec[0];
		DataTableSpec spec2 = spec[1];
		BufferedDataContainer container1 = exec.createDataContainer(spec1);
		BufferedDataContainer container2 = exec.createDataContainer(spec2);
		int index1 = 0;
		int index2 = 0;
		int count = 0;

		try (SimpleFeatureIterator iterator = collection.features()) {
			while (iterator.hasNext()) {
				SimpleFeature feature = iterator.next();
				DataCell[] cells1 = new DataCell[spec1.getNumColumns()];
				Property geoProperty = null;

				for (Property p : feature.getProperties()) {
					int column = spec1.findColumnIndex(p.getName().toString());
					Object value = p.getValue();

					if (value == null) {
						cells1[column] = DataType.getMissingCell();
					} else if (value instanceof Geometry) {
						geoProperty = p;
					} else if (value instanceof Integer) {
						cells1[column] = new IntCell((Integer) p.getValue());
					} else if (value instanceof Double) {
						cells1[column] = new DoubleCell((Double) p.getValue());
					} else if (value instanceof Boolean) {
						cells1[column] = BooleanCellFactory.create((Boolean) p.getValue());
					} else if (p.getValue().toString().isEmpty()) {
						cells1[column] = DataType.getMissingCell();
					} else {
						cells1[column] = new StringCell(p.getValue().toString());
					}
				}

				if (geoProperty == null) {
					continue;
				}

				Geometry geo = (Geometry) geoProperty.getValue();

				if (transform != null) {
					geo = JTS.transform(geo, transform);
				}

				boolean getExterior = getExteriorPolygon.getBooleanValue();
				boolean removeHoles = !getExterior && splitPolygonsWithHoles.getBooleanValue();

				for (Geometry g : EsriUtils.getSimpleGeometries(geo, removeHoles)) {
					Coordinate[] coordinates;

					if (g instanceof Polygon && getExterior) {
						coordinates = ((Polygon) g).getExteriorRing().getCoordinates();
					} else if (g instanceof Polygon || g instanceof LineString) {
						coordinates = g.getCoordinates();
					} else {
						continue;
					}

					List<StringCell> rowIdCells = new ArrayList<>();

					for (Coordinate c : coordinates) {
						DataCell[] cells2 = new DataCell[spec2.getNumColumns()];
						double lat = transform != null ? c.x : c.y;
						double lon = transform != null ? c.y : c.x;

						cells2[spec2.findColumnIndex(LATITUDE_COLUMN)] = new DoubleCell(lat);
						cells2[spec2.findColumnIndex(LONGITUDE_COLUMN)] = new DoubleCell(lon);

						String rowId;

						if (rowIdPredix.isActive()) {
							rowId = rowIdPredix.getStringValue() + "_" + index2;
						} else {
							rowId = String.valueOf(index2);
						}

						container2.addRowToTable(new DefaultRow(rowId, cells2));
						rowIdCells.add(new StringCell(rowId));
						index2++;
					}

					cells1[spec1.findColumnIndex(geoProperty.getName().toString())] = CollectionCellFactory
							.createListCell(rowIdCells);
					container1.addRowToTable(new DefaultRow(String.valueOf(index1), cells1));
					index1++;
				}

				exec.checkCanceled();
				exec.setProgress((double) count / (double) collection.size());
				count++;
			}
		} finally {
			dataStore.dispose();
			container1.close();
			container2.close();
		}

		return new BufferedDataTable[] { container1.getTable(), container2.getTable() };
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void reset() {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected DataTableSpec[] configure(final DataTableSpec[] inSpecs) throws InvalidSettingsException {
		if (shpFile.getStringValue() == null) {
			throw new InvalidSettingsException("No file name specified");
		}

		DataTableSpec[] result = null;

		try {
			ShapefileDataStore dataStore = EsriUtils.getDataStore(shpFile.getStringValue(), charset.getStringValue());

			result = createSpec(dataStore.getFeatureSource().getSchema());
			dataStore.dispose();
		} catch (InvalidPathException | IOException | UnsupportedCharsetException e) {
			throw new InvalidSettingsException(e.getMessage());
		}

		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void saveSettingsTo(final NodeSettingsWO settings) {
		shpFile.saveSettingsTo(settings);
		rowIdPredix.saveSettingsTo(settings);
		getExteriorPolygon.saveSettingsTo(settings);
		splitPolygonsWithHoles.saveSettingsTo(settings);
		charset.saveSettingsTo(settings);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void loadValidatedSettingsFrom(final NodeSettingsRO settings) throws InvalidSettingsException {
		shpFile.loadSettingsFrom(settings);
		rowIdPredix.loadSettingsFrom(settings);
		getExteriorPolygon.loadSettingsFrom(settings);

		try {
			splitPolygonsWithHoles.loadSettingsFrom(settings);
		} catch (InvalidSettingsException e) {
		}

		try {
			charset.loadSettingsFrom(settings);
		} catch (InvalidSettingsException e) {
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void validateSettings(final NodeSettingsRO settings) throws InvalidSettingsException {
		shpFile.validateSettings(settings);
		rowIdPredix.validateSettings(settings);
		getExteriorPolygon.validateSettings(settings);

		try {
			splitPolygonsWithHoles.validateSettings(settings);
		} catch (InvalidSettingsException e) {
		}

		try {
			charset.validateSettings(settings);
		} catch (InvalidSettingsException e) {
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void loadInternals(final File internDir, final ExecutionMonitor exec)
			throws IOException, CanceledExecutionException {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void saveInternals(final File internDir, final ExecutionMonitor exec)
			throws IOException, CanceledExecutionException {
	}

	private static DataTableSpec[] createSpec(SimpleFeatureType type) {
		List<DataColumnSpec> columns1 = new ArrayList<>();

		for (AttributeType t : type.getTypes()) {
			if (t == type.getGeometryDescriptor().getType()) {
				columns1.add(new DataColumnSpecCreator(type.getGeometryDescriptor().getName().toString(),
						ListCell.getCollectionType(StringCell.TYPE)).createSpec());
			} else if (t.getBinding() == Integer.class) {
				columns1.add(new DataColumnSpecCreator(t.getName().toString(), IntCell.TYPE).createSpec());
			} else if (t.getBinding() == Double.class) {
				columns1.add(new DataColumnSpecCreator(t.getName().toString(), DoubleCell.TYPE).createSpec());
			} else if (t.getBinding() == Boolean.class) {
				columns1.add(new DataColumnSpecCreator(t.getName().toString(), BooleanCell.TYPE).createSpec());
			} else {
				columns1.add(new DataColumnSpecCreator(t.getName().toString(), StringCell.TYPE).createSpec());
			}
		}

		List<DataColumnSpec> columns2 = new ArrayList<>();

		columns2.add(new DataColumnSpecCreator(LATITUDE_COLUMN, DoubleCell.TYPE).createSpec());
		columns2.add(new DataColumnSpecCreator(LONGITUDE_COLUMN, DoubleCell.TYPE).createSpec());

		return new DataTableSpec[] { new DataTableSpec(columns1.toArray(new DataColumnSpec[0])),
				new DataTableSpec(columns2.toArray(new DataColumnSpec[0])) };
	}
}
