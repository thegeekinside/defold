package com.dynamo.cr.contenteditor.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jagatoo.loaders.models.collada.stax.XMLCOLLADA;
import org.jagatoo.loaders.models.collada.stax.XMLGeometry;
import org.jagatoo.loaders.models.collada.stax.XMLInput;
import org.jagatoo.loaders.models.collada.stax.XMLMesh;
import org.jagatoo.loaders.models.collada.stax.XMLSource;

import com.dynamo.cr.contenteditor.scene.LoaderException;
import com.dynamo.cr.contenteditor.scene.Mesh;

public class ColladaUtil
{
    private static XMLInput findInput(List<XMLInput> inputs, String semantic) throws LoaderException
    {
        for (XMLInput i : inputs)
        {
            if (i.semantic.equals(semantic))
                return i;
        }
        return null;
    }

    private static HashMap<String, XMLSource> getSourcesMap( XMLMesh mesh, List<XMLSource> sources )
    {
        HashMap<String, XMLSource> sourcesMap;
        sourcesMap = new HashMap<String, XMLSource>();
        for ( int i = 0; i < sources.size(); i++ )
        {
            XMLSource source = sources.get( i );

            sourcesMap.put( source.id, source );
        }

        return sourcesMap;
    }

    public static Mesh loadMesh(InputStream is) throws IOException, XMLStreamException, LoaderException
    {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader stream_reader = factory.createXMLStreamReader(is);

        XMLCOLLADA collada = new XMLCOLLADA();
        collada.parse(stream_reader);

        if (collada.libraryGeometries.size() != 1)
        {
            throw new LoaderException("Only a single geometry is supported");
        }

        XMLGeometry geom = collada.libraryGeometries.get(0).geometries.values().iterator().next();

        XMLMesh mesh = geom.mesh;

        List<XMLSource> sources = mesh.sources;
        HashMap<String, XMLSource> sourcesMap = getSourcesMap( mesh, sources );

        XMLInput vpos_input = findInput(mesh.vertices.inputs, "POSITION");
        XMLInput vertex_input = findInput(mesh.triangles.inputs, "VERTEX");
        XMLInput normal_input = findInput(mesh.triangles.inputs, "NORMAL");
        XMLInput texcoord_input = findInput(mesh.triangles.inputs, "TEXCOORD");

        if (mesh.triangles.inputs.size() == 0)
            throw new LoaderException("No inputs in triangles");

        int stride = 0;
        for (XMLInput i : mesh.triangles.inputs)
        {
            stride = Math.max(stride, i.offset);
        }
        stride += 1;

        XMLSource positions = sourcesMap.get(vpos_input.source);
        XMLSource normals = sourcesMap.get(normal_input.source);
        XMLSource texcoords = null;

        List<Float> position_list = new ArrayList<Float>();
        List<Float> normal_list = new ArrayList<Float>();
        List<Float> texcoord_list = null;

        if (texcoord_input != null) {
            texcoord_list = new ArrayList<Float>();
            texcoords = sourcesMap.get(texcoord_input.source);
        }

        float meter = collada.asset.unit.meter;
        for (int i = 0; i < mesh.triangles.count; ++i)
        {
            int idx = i * stride * 3 + vertex_input.offset;

            for (int j = 0; j < 3; ++j)
            {
                int tri_ind = mesh.triangles.p[idx + stride * j];

                float px = positions.floatArray.floats[3 * tri_ind + 0];
                float py = positions.floatArray.floats[3 * tri_ind + 1];
                float pz = positions.floatArray.floats[3 * tri_ind + 2];

                position_list.add(px * meter);
                position_list.add(py * meter);
                position_list.add(pz * meter);
            }

            idx = i * stride * 3 + normal_input.offset;
            for (int j = 0; j < 3; ++j)
            {
                int tri_ind = mesh.triangles.p[idx + stride * j];

                float px = normals.floatArray.floats[3 * tri_ind + 0];
                float py = normals.floatArray.floats[3 * tri_ind + 1];
                float pz = normals.floatArray.floats[3 * tri_ind + 2];

                normal_list.add(px);
                normal_list.add(py);
                normal_list.add(pz);
            }

            if (texcoord_input != null) {
                idx = i * stride * 2 + texcoord_input.offset;
                for (int j = 0; j < 3; ++j)
                {
                    int tri_ind = mesh.triangles.p[idx + stride * j];

                    float uvx = texcoords.floatArray.floats[2 * tri_ind + 0];
                    float uvy = texcoords.floatArray.floats[2 * tri_ind + 1];

                    texcoord_list.add(uvx);
                    texcoord_list.add(uvy);
                }
            }

        }

        return new Mesh(position_list, normal_list, texcoord_list);
    }

}
