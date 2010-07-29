#include <dlib/log.h>
#include <ddf/ddf.h>
#include <dlib/dstrings.h>
#include <stdint.h>
#include "resource.h"
#include "../proto/physics_ddf.h"
#include <graphics/graphics_device.h>
#include <graphics/graphics_ddf.h>
#include <physics/physics.h>
#include <render/fontrenderer.h>
#include <render/render_ddf.h>
#include "resource_creation.h"

namespace dmGameSystem
{

    static dmGraphics::TextureFormat TextureImageToTextureFormat(dmGraphics::TextureImage* image)
    {
        switch (image->m_Format)
        {
        case dmGraphics::TextureImage::LUMINANCE:
            return dmGraphics::TEXTURE_FORMAT_LUMINANCE;
            break;
        case dmGraphics::TextureImage::RGB:
            return dmGraphics::TEXTURE_FORMAT_RGB;
            break;
        case dmGraphics::TextureImage::RGBA:
            return dmGraphics::TEXTURE_FORMAT_RGBA;
            break;
        case dmGraphics::TextureImage::RGB_DXT1:
            return dmGraphics::TEXTURE_FORMAT_RGB_DXT1;
            break;
        case dmGraphics::TextureImage::RGBA_DXT1:
            return dmGraphics::TEXTURE_FORMAT_RGBA_DXT1;
            break;
        case dmGraphics::TextureImage::RGBA_DXT3:
            return dmGraphics::TEXTURE_FORMAT_RGBA_DXT3;
            break;
        case dmGraphics::TextureImage::RGBA_DXT5:
            return dmGraphics::TEXTURE_FORMAT_RGBA_DXT5;
            break;

        default:
            assert(0);
        }

    }

    dmResource::CreateResult TextureCreate(dmResource::HFactory factory,
                                           void* context,
                                           const void* buffer, uint32_t buffer_size,
                                           dmResource::SResourceDescriptor* resource,
                                           const char* filename)
    {
        dmGraphics::TextureImage* image;
        dmDDF::Result e = dmDDF::LoadMessage<dmGraphics::TextureImage>(buffer, buffer_size, (&image));
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::CREATE_RESULT_UNKNOWN;
        }

        dmGraphics::TextureFormat format;
        format = TextureImageToTextureFormat(image);
        dmGraphics::HTexture texture = dmGraphics::CreateTexture(image->m_Width, image->m_Height, format);

        int w = image->m_Width;
        int h = image->m_Height;
        for (int i = 0; i < (int) image->m_MipMapOffset.m_Count; ++i)
        {
            dmGraphics::SetTextureData(texture, i, w, h, 0, format, &image->m_Data[image->m_MipMapOffset[i]], image->m_MipMapSize[i]);
            w >>= 1;
            h >>= 1;
            if (w == 0) w = 1;
            if (h == 0) h = 1;
        }

        dmDDF::FreeMessage(image);

        resource->m_Resource = (void*) texture;
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult TextureDestroy(dmResource::HFactory factory,
                                            void* context,
                                            dmResource::SResourceDescriptor* resource)
    {
        dmGraphics::DestroyTexture((dmGraphics::HTexture) resource->m_Resource);
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult VertexProgramCreate(dmResource::HFactory factory,
                                                 void* context,
                                                 const void* buffer, uint32_t buffer_size,
                                                 dmResource::SResourceDescriptor* resource,
                                                 const char* filename)
    {

        dmGraphics::HVertexProgram prog = dmGraphics::CreateVertexProgram(buffer, buffer_size);
        if (prog == 0 )
            return dmResource::CREATE_RESULT_UNKNOWN;

        resource->m_Resource = (void*) prog;
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult VertexProgramDestroy(dmResource::HFactory factory,
                                                  void* context,
                                                  dmResource::SResourceDescriptor* resource)
    {
        dmGraphics::DestroyVertexProgram((dmGraphics::HVertexProgram) resource->m_Resource);
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult FragmentProgramCreate(dmResource::HFactory factory,
                                                   void* context,
                                                   const void* buffer, uint32_t buffer_size,
                                                   dmResource::SResourceDescriptor* resource,
                                                   const char* filename)
    {

        dmGraphics::HFragmentProgram prog = dmGraphics::CreateFragmentProgram(buffer, buffer_size);
        if (prog == 0 )
            return dmResource::CREATE_RESULT_UNKNOWN;

        resource->m_Resource = (void*) prog;
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult FragmentProgramDestroy(dmResource::HFactory factory,
                                                    void* context,
                                                    dmResource::SResourceDescriptor* resource)
    {
        dmGraphics::DestroyFragmentProgram((dmGraphics::HFragmentProgram) resource->m_Resource);
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult ConvexShapeCreate(dmResource::HFactory factory,
                                               void* context,
                                               const void* buffer, uint32_t buffer_size,
                                               dmResource::SResourceDescriptor* resource,
                                               const char* filename)
    {
        dmPhysicsDDF::ConvexShape* convex_shape;
        dmDDF::Result e = dmDDF::LoadMessage<dmPhysicsDDF::ConvexShape>(buffer, buffer_size, &convex_shape);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::CREATE_RESULT_UNKNOWN;
        }

        switch (convex_shape->m_ShapeType)
        {
        case dmPhysicsDDF::ConvexShape::TYPE_SPHERE:
            if (convex_shape->m_Data.m_Count != 1)
            {
                dmLogError("Invalid sphere shape");
                return dmResource::CREATE_RESULT_FORMAT_ERROR;
            }
            resource->m_Resource = dmPhysics::NewSphereShape(convex_shape->m_Data[0]);
            break;
        case dmPhysicsDDF::ConvexShape::TYPE_BOX:
            if (convex_shape->m_Data.m_Count != 3)
            {
                dmLogError("Invalid box shape");
                return dmResource::CREATE_RESULT_FORMAT_ERROR;
            }
            resource->m_Resource = dmPhysics::NewBoxShape(Vector3(convex_shape->m_Data[0], convex_shape->m_Data[1], convex_shape->m_Data[2]));
            break;
        case dmPhysicsDDF::ConvexShape::TYPE_CAPSULE:
            if (convex_shape->m_Data.m_Count != 2)
            {
                dmLogError("Invalid capsule shape");
                return dmResource::CREATE_RESULT_FORMAT_ERROR;
            }
            resource->m_Resource = dmPhysics::NewCapsuleShape(convex_shape->m_Data[0], convex_shape->m_Data[1]);
            break;
        case dmPhysicsDDF::ConvexShape::TYPE_HULL:
            if (convex_shape->m_Data.m_Count < 9)
            {
                dmLogError("Invalid hull shape");
                return dmResource::CREATE_RESULT_FORMAT_ERROR;
            }
            resource->m_Resource = dmPhysics::NewConvexHullShape(&convex_shape->m_Data[0], convex_shape->m_Data.m_Count);
            break;
        }

        dmDDF::FreeMessage(convex_shape);
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult ConvexShapeDestroy(dmResource::HFactory factory,
                                                void* context,
                                                dmResource::SResourceDescriptor* resource)
    {
        dmPhysics::DeleteCollisionShape((dmPhysics::HCollisionShape) resource->m_Resource);
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult CollisionObjectCreate(dmResource::HFactory factory,
                                             void* context,
                                             const void* buffer, uint32_t buffer_size,
                                             dmResource::SResourceDescriptor* resource,
                                             const char* filename)
    {
        dmPhysicsDDF::CollisionObjectDesc* collision_object_desc;
        dmDDF::Result e = dmDDF::LoadMessage<dmPhysicsDDF::CollisionObjectDesc>(buffer, buffer_size, &collision_object_desc);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::CREATE_RESULT_UNKNOWN;
        }

        dmPhysics::HCollisionShape collision_shape;
        dmResource::FactoryResult FACTORY_RESULT;
        FACTORY_RESULT = dmResource::Get(factory, collision_object_desc->m_CollisionShape, (void**) &collision_shape);
        if (FACTORY_RESULT != dmResource::FACTORY_RESULT_OK)
        {
            dmDDF::FreeMessage(collision_object_desc);
            return dmResource::CREATE_RESULT_UNKNOWN; // TODO: Translate error... we need a new function...
        }

        CollisionObjectPrototype* collision_object_prototype = new CollisionObjectPrototype();
        collision_object_prototype->m_CollisionShape = collision_shape;
        collision_object_prototype->m_Mass = collision_object_desc->m_Mass;
        collision_object_prototype->m_Type = (dmPhysics::CollisionObjectType)collision_object_desc->m_Type;
        collision_object_prototype->m_Group = (uint16_t)collision_object_desc->m_Group;
        collision_object_prototype->m_Mask = 0;
        for (uint32_t i = 0; i < collision_object_desc->m_Mask.m_Count; ++i)
            collision_object_prototype->m_Mask |= collision_object_desc->m_Mask[i];
        resource->m_Resource = (void*) collision_object_prototype;

        dmDDF::FreeMessage(collision_object_desc);

        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult CollisionObjectDestroy(dmResource::HFactory factory,
                                              void* context,
                                              dmResource::SResourceDescriptor* resource)
    {
        CollisionObjectPrototype* collision_object_prototype = (CollisionObjectPrototype*)resource->m_Resource;
        dmResource::Release(factory, collision_object_prototype->m_CollisionShape);
        delete collision_object_prototype;
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult ImageFontCreate(dmResource::HFactory factory,
                                          void* context,
                                          const void* buffer, uint32_t buffer_size,
                                          dmResource::SResourceDescriptor* resource,
                                          const char* filename)
    {
        dmRender::HImageFont font = dmRender::NewImageFont(buffer, buffer_size);
        if (font)
        {
            resource->m_Resource = (void*)font;
            return dmResource::CREATE_RESULT_OK;
        }
        else
        {
            return dmResource::CREATE_RESULT_UNKNOWN;
        }
    }

    dmResource::CreateResult ImageFontDestroy(dmResource::HFactory factory,
                                           void* context,
                                           dmResource::SResourceDescriptor* resource)
    {
        dmRender::DeleteImageFont((dmRender::HImageFont) resource->m_Resource);

        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult FontCreate(dmResource::HFactory factory,
                                     void* context,
                                     const void* buffer, uint32_t buffer_size,
                                     dmResource::SResourceDescriptor* resource,
                                     const char* filename)
    {
        dmRender::FontDesc* font_desc;
        dmDDF::Result e = dmDDF::LoadMessage<dmRender::FontDesc>(buffer, buffer_size, &font_desc);
        if ( e != dmDDF::RESULT_OK )
        {
            return dmResource::CREATE_RESULT_UNKNOWN;
        }

        dmRender::HImageFont image_font;
        dmGraphics::HVertexProgram vertex_program;
        dmGraphics::HFragmentProgram fragment_program;

        char vertex_program_buf[1024];
        DM_SNPRINTF(vertex_program_buf, sizeof(vertex_program_buf), "%s.arbvp", font_desc->m_VertexProgram);
        char fragment_program_buf[1024];
        DM_SNPRINTF(fragment_program_buf, sizeof(fragment_program_buf), "%s.arbfp", font_desc->m_FragmentProgram);

        dmResource::Get(factory, font_desc->m_Font, (void**) &image_font);
        dmResource::Get(factory, vertex_program_buf, (void**) &vertex_program);
        dmResource::Get(factory, fragment_program_buf, (void**) &fragment_program);

        if (image_font == 0 || vertex_program == 0 || fragment_program == 0)
        {
            if (image_font) dmResource::Release(factory, image_font);
            if (vertex_program) dmResource::Release(factory, (void*) vertex_program);
            if (fragment_program) dmResource::Release(factory, (void*) fragment_program);
            dmDDF::FreeMessage(font_desc);
            return dmResource::CREATE_RESULT_UNKNOWN;
        }

        dmRender::HFont font = dmRender::NewFont(image_font);

        dmRender::SetVertexProgram(font, vertex_program);
        dmRender::SetFragmentProgram(font, fragment_program);
        resource->m_Resource = (void*)font;
        dmDDF::FreeMessage(font_desc);
        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::CreateResult FontDestroy(dmResource::HFactory factory,
                                      void* context,
                                      dmResource::SResourceDescriptor* resource)
    {
        dmRender::HFont font = (dmRender::HFont) resource->m_Resource;
        dmResource::Release(factory, (void*) dmRender::GetImageFont(font));
        dmResource::Release(factory, (void*) dmRender::GetVertexProgram(font));
        dmResource::Release(factory, (void*) dmRender::GetFragmentProgram(font));

        dmRender::DeleteFont(font);

        return dmResource::CREATE_RESULT_OK;
    }

    dmResource::FactoryResult RegisterResources(dmResource::HFactory factory)
    {
        dmResource::FactoryResult e;
        e = dmResource::RegisterType(factory, "texture", 0, &TextureCreate, &TextureDestroy, 0);
        if( e != dmResource::FACTORY_RESULT_OK ) return e;

        e = dmResource::RegisterType(factory, "arbvp", 0, &VertexProgramCreate, &VertexProgramDestroy, 0);
        if( e != dmResource::FACTORY_RESULT_OK ) return e;

        e = dmResource::RegisterType(factory, "arbfp", 0, &FragmentProgramCreate, &FragmentProgramDestroy, 0);
        if( e != dmResource::FACTORY_RESULT_OK ) return e;

        e = dmResource::RegisterType(factory, "convexshape", 0, &ConvexShapeCreate, &ConvexShapeDestroy, 0);
        if( e != dmResource::FACTORY_RESULT_OK ) return e;

        e = dmResource::RegisterType(factory, "collisionobject", 0, &CollisionObjectCreate, &CollisionObjectDestroy, 0);
        if( e != dmResource::FACTORY_RESULT_OK ) return e;

        e = dmResource::RegisterType(factory, "imagefont", 0, &ImageFontCreate, &ImageFontDestroy, 0);
        if( e != dmResource::FACTORY_RESULT_OK ) return e;

        e = dmResource::RegisterType(factory, "font", 0, &FontCreate, &FontDestroy, 0);
        if( e != dmResource::FACTORY_RESULT_OK ) return e;

        return dmResource::FACTORY_RESULT_OK;
    }
}
