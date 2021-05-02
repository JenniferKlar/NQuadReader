package graphX;

import org.apache.spark.SparkConf;

import scala.reflect.ClassTag;
import scala.Tuple2;

import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.graphx.Edge;
import org.apache.spark.graphx.EdgeTriplet;
import org.apache.spark.graphx.Graph;
import java.util.ArrayList;
import java.util.List;
import org.apache.spark.storage.StorageLevel;

public class NQuadReader {

	@SuppressWarnings("unused")
	public static void main(String[] args) {

		SparkConf sparkConf = new SparkConf().setAppName("NQuadReader").setMaster("local[*]")
				.set("spark.executor.memory", "2g").set("spark.executor.cores", "1")
				.set("spark.dynamicAllocation.enabled", "true")
				.set("spark.serializer", "org.apache.spark.serializer.JavaSerializer");
		JavaSparkContext jsc = new JavaSparkContext(sparkConf);
		ClassTag<Object> objectTag = scala.reflect.ClassTag$.MODULE$.apply(Object.class);
		ClassTag<Relation> relationTag = scala.reflect.ClassTag$.MODULE$.apply(Relation.class);

		Graph<Object, Relation> quadGraph = GraphGenerator.generateGraph(jsc, objectTag, relationTag);
		Graph<Object, Relation> sliceDiceGraph = sliceDice(quadGraph, jsc, objectTag, relationTag,"Level_Importance_All-All","Level_Aircraft_All-All", "Level_Location_All-All", "Level_Date_All-All");
		Graph<Object, Relation> mergedGraph = merge(quadGraph, jsc, "Level_Importance_Package", "Level_Aircraft_All", "Level_Location_Region", "Level_Date_Year");
		
		//todo
		Graph<Object, Relation> abstractGraph;

		jsc.close();

	}

	private static Graph<Object, Relation> merge(Graph<Object, Relation> graph, JavaSparkContext jsc, String string, String string2,
			String string3, String string4) {
		List<Object> importance = getCellsWithLevel(graph, "hasImportance", "Level_Importance_Package");
		List<Object> aircraft = getCellsWithLevel(graph, "hasAircraft", "Level_Aircraft_All");
		List<Object> location = getCellsWithLevel(graph, "hasLocation", "Level_Location_Region");
		List<Object> date = getCellsWithLevel(graph, "hasDate", "Level_Date_Year");

		List<Object> result = new ArrayList<>();

		// compare lists --> if cell is in each --> return it (satisfies all four
		// dimensions)
		for (int i = 0; i < importance.size(); i++) {
			Object o = importance.get(i);
			if (aircraft.contains(o) && location.contains(o) && date.contains(o)) {
				result.add(o);
			}
		}
		getVerticesAttributes(graph, result)
		.forEach(x -> {
			List<String> covered = new ArrayList<>();
			covered.addAll(GraphGenerator.getCoverage(x._2.toString(), jsc).map(z -> z+"-mod").collect());
			graph.triplets().toJavaRDD().foreach(y -> {
				//System.out.println(y.attr().getContext().toString());
				if(covered.contains(y.attr().getContext().toString())) {
					y.attr().setContext(x._2.toString()+"-mod");
				}
			});
			;});
		return graph;
	}

	private static List<Tuple2<Object, Object>> getVerticesAttributes(Graph<Object, Relation> graph, List<Object> ids) {
		List<Tuple2<Object, Object>> result = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) {
			int j = i;
			result.addAll(graph.vertices().toJavaRDD().filter(x -> x._1.equals(ids.get(j))).collect());
		}
		return result;
	}

	private static Graph<Object, Relation> sliceDice(Graph<Object, Relation> graph, JavaSparkContext jsc,
			ClassTag<Object> objectTag, ClassTag<Relation> relationTag, String importanceValue, String aircraftValue,
			String locationValue, String dateValue) {
		List<Object> cellIds = getCellsWithDimValues(graph, jsc, objectTag, relationTag, importanceValue, aircraftValue,
				locationValue, dateValue);
		List<String> mods = getMods(graph, cellIds);
		List<Edge<Relation>> rels = new ArrayList<>();
		for (int i = 0; i < mods.size(); i++) {
			int j = i;
			rels.addAll(graph.edges().toJavaRDD()
					.filter(e -> ((Resource) e.attr().getContext()).getValue().contains(mods.get(j))).collect());
		}

		List<Tuple2<Object, Object>> moduleVertices = new ArrayList<>();
		List<Object> vertices = new ArrayList<Object>();
		// all sources and destinations of all mods
		rels.forEach(x -> vertices.add(x.dstId()));
		rels.forEach(x -> vertices.add(x.srcId()));
		// filter vertices
		graph.vertices().toJavaRDD().collect().forEach(v -> {
			if (vertices.contains(v._1)) {
				moduleVertices.add(v);
			}
		});
		jsc.parallelize(moduleVertices);
		jsc.parallelize(rels);
		// create graph obejct from filtered edges and vertices
		Graph<Object, Relation> resultGraph = Graph.apply(jsc.parallelize(moduleVertices).rdd(),
				jsc.parallelize(rels).rdd(), "", StorageLevel.MEMORY_ONLY(), StorageLevel.MEMORY_ONLY(), objectTag,
				relationTag);

		return resultGraph;
	}

	private static List<Object> getCellsWithDimValues(Graph<Object, Relation> graph, JavaSparkContext jsc,
			ClassTag<Object> objectTag, ClassTag<Relation> relationTag, String importanceValue, String aircraftValue,
			String locationValue, String dateValue) {
		// get cells for each dimension individually
		List<Object> importance = getCellsWithDimValueAndCovered(graph, objectTag, relationTag, "hasImportance",
				importanceValue, jsc);
		List<Object> aircraft = getCellsWithDimValueAndCovered(graph, objectTag, relationTag, "hasAircraft",
				aircraftValue, jsc);
		List<Object> location = getCellsWithDimValueAndCovered(graph, objectTag, relationTag, "hasLocation",
				locationValue, jsc);
		List<Object> date = getCellsWithDimValueAndCovered(graph, objectTag, relationTag, "hasDate", dateValue, jsc);
		List<Object> result = new ArrayList<>();

		// compare lists --> if cell is in each --> return it (satisfies all four
		// dimensions)
		for (int i = 0; i < importance.size(); i++) {
			Object o = importance.get(i);
			if (aircraft.contains(o) && location.contains(o) && date.contains(o)) {
				result.add(o);
			}
		}
		return result;
	}

	private static List<Object> getCellsWithDimValueAndCovered(Graph<Object, Relation> graph,
			ClassTag<Object> objectTag, ClassTag<Relation> relationTag, String dimension, String value,
			JavaSparkContext jsc) {

		ArrayList<String> lowerInstances = new ArrayList<>();
		lowerInstances.add(value);
		if (GraphGenerator.getLowerInstances(value, jsc) != null) {
			lowerInstances.addAll(GraphGenerator.getLowerInstances(value, jsc).collect());
		}

		ArrayList<Object> result = new ArrayList<>();
		for (int i = 0; i < lowerInstances.size(); i++) {
			int j = i;
			result.addAll(graph.triplets().toJavaRDD()
					.filter(triplet -> ((Resource) triplet.attr().getRelationship()).getValue().contains(dimension)
							&& triplet.dstAttr().toString().contains(lowerInstances.get(j)))
					.map(triplet -> triplet.srcId()).collect());
		}
		return result;
	}

	// get all Mods (string) that belong to the cells that have correct dimensions
	private static List<String> getMods(Graph<Object, Relation> quadGraph, List<Object> cellIds) {
		List<String> result = new ArrayList<>();
		// get mods
		List<EdgeTriplet<Object, Relation>> modules = quadGraph.triplets().toJavaRDD()
				.filter(x -> ((Resource) x.attr().getRelationship()).getValue().contains("hasAssertedModule"))
				.collect();
		for (int i = 0; i < modules.size(); i++) {
			for (int j = 0; j < cellIds.size(); j++) {
				// id cellId contains mod --> add it to result
				if (modules.get(i).srcId() == (long) cellIds.get(j)) {
					result.add(modules.get(i).dstAttr().toString());
				}
			}
		}
		return result;
	}

	private static List<Object> getCellsWithLevel(Graph<Object, Relation> graph, String dimension, String level) {
		ArrayList<String> levels = new ArrayList<>();
		levels.add(level);

		ArrayList<Long> aList = new ArrayList<>();
		for (int i = 0; i < levels.size(); i++) {
			int j = i;
			aList.addAll(graph.triplets().toJavaRDD()
					.filter(triplet -> ((Resource) triplet.attr().getRelationship()).getValue().contains("atLevel")
							&& triplet.dstAttr().toString().contains(levels.get(j)))
					.map(triplet -> triplet.srcId()).collect());
		}
		List<EdgeTriplet<Object, Relation>> g = graph.triplets().toJavaRDD()
				.filter(triplet -> ((Resource) triplet.attr().getRelationship()).getValue().contains(dimension))
				.collect();

		List<Object> result = new ArrayList<>();
		for (int i = 0; i < g.size(); i++) {
			if (aList.contains(g.get(i).dstId())) {
				result.add(g.get(i).srcId());
			}
		}
		return result;
	}

}
